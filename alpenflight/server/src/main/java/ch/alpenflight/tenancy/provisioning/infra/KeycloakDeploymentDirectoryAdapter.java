package ch.alpenflight.tenancy.provisioning.infra;

import ch.alpenflight.platform.keycloak.BearerTokenInterceptor;
import ch.alpenflight.platform.keycloak.KeycloakAdminProperties;
import ch.alpenflight.platform.keycloak.KeycloakAdminTokenSupplier;
import ch.alpenflight.platform.keycloak.RedactingRestClientInterceptor;
import ch.alpenflight.tenancy.provisioning.domain.KeycloakDeploymentDirectory;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * Keycloak adapter for the S-138 provisioning Phase B reconcile port.
 * Talks to the realm via the {@code alpenflight-backend-admin} machine
 * client (S-052 plumbing) extended with the {@code manage-groups} scope
 * (S-138). Every method is idempotent — see the port javadoc for the
 * "create-if-absent + state-if-not-already" semantics each call enforces.
 *
 * <p>Sibling-friendly: builds its own {@link RestClient} on top of
 * {@link KeycloakAdminTokenSupplier} + {@link BearerTokenInterceptor} +
 * {@link RedactingRestClientInterceptor} so the adapter doesn't pull in
 * the user-directory adapter's public surface.
 */
@Component
public class KeycloakDeploymentDirectoryAdapter implements KeycloakDeploymentDirectory {

    private final RestClient http;
    private final KeycloakAdminProperties props;
    private final ObjectMapper objectMapper;

    public KeycloakDeploymentDirectoryAdapter(KeycloakAdminTokenSupplier tokens,
                                              KeycloakAdminProperties props,
                                              ObjectMapper objectMapper) {
        this.http = RestClient.builder()
                .requestInterceptor(new BearerTokenInterceptor(tokens))
                .requestInterceptor(RedactingRestClientInterceptor.INSTANCE)
                .build();
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public UUID findOrCreateDeploymentGroup(UUID deploymentId) {
        Objects.requireNonNull(deploymentId, "deploymentId");
        String name = deploymentGroupName(deploymentId);

        UUID existingId = findGroupIdByName(name);
        if (existingId != null) {
            return existingId;
        }
        try {
            ResponseEntity<Void> response = http.post()
                    .uri(props.adminBase() + "/groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("name", name))
                    .retrieve()
                    .toBodilessEntity();
            URI location = Objects.requireNonNull(response, "null response from groups POST")
                    .getHeaders().getLocation();
            if (location != null) {
                return uuidFromLocation(location, "create group");
            }
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() != 409) {
                throw new KeycloakProvisioningException(
                        "Keycloak group create failed (status " + e.getStatusCode().value() + ")", e);
            }
            // Concurrent create won — fall through to the read path.
        }
        UUID afterRace = findGroupIdByName(name);
        if (afterRace == null) {
            throw new KeycloakProvisioningException(
                    "Keycloak group create returned no Location header and post-race read missed " + name);
        }
        return afterRace;
    }

    @Override
    public void addUserToGroupIfAbsent(UUID userKeycloakSub, UUID groupId) {
        Objects.requireNonNull(userKeycloakSub, "userKeycloakSub");
        Objects.requireNonNull(groupId, "groupId");
        try {
            String body = http.get()
                    .uri(props.adminBase() + "/users/" + userKeycloakSub + "/groups")
                    .retrieve()
                    .body(String.class);
            List<KeycloakNamedRef> existing = readListOf(body);
            for (KeycloakNamedRef ref : existing) {
                if (groupId.equals(ref.id())) {
                    return;
                }
            }
        } catch (HttpStatusCodeException e) {
            throw new KeycloakProvisioningException(
                    "Keycloak read user groups (status " + e.getStatusCode().value() + ")", e);
        }
        try {
            http.put()
                    .uri(props.adminBase() + "/users/" + userKeycloakSub + "/groups/" + groupId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            throw new KeycloakProvisioningException(
                    "Keycloak add-user-to-group (status " + e.getStatusCode().value() + ")", e);
        }
    }

    @Override
    public UUID findOrCreateClubAdminRole(UUID deploymentId, UUID clubId) {
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(clubId, "clubId");
        String name = clubAdminRoleName(deploymentId, clubId);

        UUID existingId = findRealmRoleIdByName(name);
        if (existingId != null) {
            return existingId;
        }
        try {
            http.post()
                    .uri(props.adminBase() + "/roles")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("name", name))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() != 409) {
                throw new KeycloakProvisioningException(
                        "Keycloak realm-role create failed (status " + e.getStatusCode().value() + ")", e);
            }
        }
        UUID afterRace = findRealmRoleIdByName(name);
        if (afterRace == null) {
            throw new KeycloakProvisioningException(
                    "Keycloak realm-role read-after-create missed " + name);
        }
        return afterRace;
    }

    @Override
    public void assignRoleIfAbsent(UUID userKeycloakSub, UUID roleId, String roleName) {
        Objects.requireNonNull(userKeycloakSub, "userKeycloakSub");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(roleName, "roleName");
        try {
            String body = http.get()
                    .uri(props.adminBase() + "/users/" + userKeycloakSub + "/role-mappings/realm")
                    .retrieve()
                    .body(String.class);
            List<KeycloakNamedRef> existing = readListOf(body);
            for (KeycloakNamedRef ref : existing) {
                if (roleId.equals(ref.id())) {
                    return;
                }
            }
        } catch (HttpStatusCodeException e) {
            throw new KeycloakProvisioningException(
                    "Keycloak read realm role-mappings (status " + e.getStatusCode().value() + ")", e);
        }
        try {
            http.post()
                    .uri(props.adminBase() + "/users/" + userKeycloakSub + "/role-mappings/realm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of(Map.of("id", roleId.toString(), "name", roleName)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            throw new KeycloakProvisioningException(
                    "Keycloak grant realm role (status " + e.getStatusCode().value() + ")", e);
        }
    }

    @Override
    public void setUserAttribute(UUID userKeycloakSub, String attributeName, List<String> values) {
        Objects.requireNonNull(userKeycloakSub, "userKeycloakSub");
        Objects.requireNonNull(attributeName, "attributeName");
        Objects.requireNonNull(values, "values");
        try {
            http.put()
                    .uri(props.adminBase() + "/users/" + userKeycloakSub)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("attributes", Map.of(attributeName, values)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            throw new KeycloakProvisioningException(
                    "Keycloak set user attribute (status " + e.getStatusCode().value() + ")", e);
        }
    }

    private @Nullable UUID findGroupIdByName(String name) {
        String uri = UriComponentsBuilder.fromUriString(props.adminBase() + "/groups")
                .queryParam("search", name)
                .queryParam("exact", true)
                .build()
                .toUriString();
        try {
            String body = http.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);
            List<KeycloakNamedRef> matches = readListOf(body);
            for (KeycloakNamedRef ref : matches) {
                if (name.equals(ref.name())) {
                    return ref.id();
                }
            }
            return null;
        } catch (HttpStatusCodeException e) {
            throw new KeycloakProvisioningException(
                    "Keycloak group search (status " + e.getStatusCode().value() + ")", e);
        }
    }

    private @Nullable UUID findRealmRoleIdByName(String name) {
        try {
            String body = http.get()
                    .uri(props.adminBase() + "/roles/" + name)
                    .retrieve()
                    .body(String.class);
            KeycloakNamedRef ref = readNamed(body);
            return ref == null ? null : ref.id();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 404) {
                return null;
            }
            throw new KeycloakProvisioningException(
                    "Keycloak realm-role lookup (status " + e.getStatusCode().value() + ")", e);
        }
    }

    private List<KeycloakNamedRef> readListOf(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    body,
                    objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, KeycloakNamedRef.class));
        } catch (Exception e) {
            throw new KeycloakProvisioningException("Keycloak: malformed JSON list", e);
        }
    }

    private @Nullable KeycloakNamedRef readNamed(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(body, KeycloakNamedRef.class);
        } catch (Exception e) {
            throw new KeycloakProvisioningException("Keycloak: malformed JSON object", e);
        }
    }

    private static String deploymentGroupName(UUID deploymentId) {
        return "deployment-" + deploymentId;
    }

    private static String clubAdminRoleName(UUID deploymentId, UUID clubId) {
        return "deployment-" + deploymentId + "-club-" + clubId + "-admin";
    }

    private static UUID uuidFromLocation(URI location, String action) {
        String path = location.getPath();
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash + 1 >= path.length()) {
            throw new KeycloakProvisioningException(
                    "Keycloak " + action + ": malformed Location " + path);
        }
        try {
            return UUID.fromString(path.substring(lastSlash + 1));
        } catch (IllegalArgumentException e) {
            throw new KeycloakProvisioningException(
                    "Keycloak " + action + ": Location tail is not a UUID — " + path, e);
        }
    }

    /** Minimal projection over Keycloak's {id, name, ...} shape. */
    record KeycloakNamedRef(@Nullable UUID id, @Nullable String name) {}

    /**
     * Adapter-local runtime exception. Production callers (S-141 retry
     * job) catch + leave the row {@code PENDING}; only unit tests assert
     * on the type. NOT a {@code @ControllerAdvice}-translated exception —
     * Phase B doesn't surface to the SPA directly.
     */
    public static final class KeycloakProvisioningException extends RuntimeException {
        public KeycloakProvisioningException(String message) {
            super(message);
        }
        public KeycloakProvisioningException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
