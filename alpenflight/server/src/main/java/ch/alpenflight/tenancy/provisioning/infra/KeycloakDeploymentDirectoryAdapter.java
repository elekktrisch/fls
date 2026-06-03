package ch.alpenflight.tenancy.provisioning.infra;

import ch.alpenflight.platform.keycloak.BearerTokenInterceptor;
import ch.alpenflight.platform.keycloak.KeycloakAdminProperties;
import ch.alpenflight.platform.keycloak.KeycloakAdminTokenSupplier;
import ch.alpenflight.platform.keycloak.RedactingRestClientInterceptor;
import ch.alpenflight.tenancy.provisioning.domain.KeycloakDeploymentDirectory;
import ch.alpenflight.tenancy.provisioning.domain.KeycloakDeploymentNames;
import java.net.URI;
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
 * Keycloak adapter for the deployment-directory reconcile port. Talks to
 * the realm via the {@code alpenflight-backend-admin} machine client
 * (the same plumbing the user-directory adapter uses) extended with the
 * {@code manage-groups} scope. Every method is idempotent — see the
 * port javadoc for the "create-if-absent + state-if-not-already"
 * semantics each call enforces.
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
        String name = KeycloakDeploymentNames.deploymentGroupName(deploymentId);

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
                throw transportFailure("group create", e);
            }
            // Concurrent create won — the read-after-create below
            // resolves the id the other thread minted.
        }
        UUID afterRace = findGroupIdByName(name);
        if (afterRace == null) {
            throw new KeycloakProvisioningException(
                    "group create returned no Location header and post-race read missed " + name);
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
            throw transportFailure("read user groups", e);
        }
        try {
            http.put()
                    .uri(props.adminBase() + "/users/" + userKeycloakSub + "/groups/" + groupId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 409) {
                // Already a member — pre-check missed the race; idempotent.
                return;
            }
            throw transportFailure("add-user-to-group", e);
        }
    }

    @Override
    public UUID findOrCreateClubAdminRole(UUID deploymentId, UUID clubId) {
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(clubId, "clubId");
        String name = KeycloakDeploymentNames.clubAdminRoleName(deploymentId, clubId);

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
                throw transportFailure("realm-role create", e);
            }
        }
        UUID afterRace = findRealmRoleIdByName(name);
        if (afterRace == null) {
            throw new KeycloakProvisioningException(
                    "realm-role read-after-create missed " + name);
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
            throw transportFailure("read realm role-mappings", e);
        }
        try {
            http.post()
                    .uri(props.adminBase() + "/users/" + userKeycloakSub + "/role-mappings/realm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of(Map.of("id", roleId.toString(), "name", roleName)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            throw transportFailure("grant realm role", e);
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
            throw transportFailure("set user attribute", e);
        }
    }

    @Override
    public UUID provisionClubAdminIdentity(UUID clubId, String username, String email,
                                           String firstName, String lastName) {
        Objects.requireNonNull(clubId, "clubId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(firstName, "firstName");
        Objects.requireNonNull(lastName, "lastName");

        UUID sub = createClubAdminUserOrResolveExisting(
                clubId, username, email, firstName, lastName);

        // Static realm role (realm-export fixture), not a per-Deployment
        // dynamic role — so it's resolved by its fixed name, not minted.
        String roleName = KeycloakDeploymentNames.CLUB_ADMINISTRATOR_REALM_ROLE;
        UUID roleId = findRealmRoleIdByName(roleName);
        if (roleId == null) {
            throw new KeycloakProvisioningException(
                    "realm role " + roleName + " missing from realm — cannot make migrated "
                            + "club admin loginable (expected in realm-export.json)");
        }
        assignRoleIfAbsent(sub, roleId, roleName);
        return sub;
    }

    private UUID createClubAdminUserOrResolveExisting(UUID clubId, String username, String email,
                                                      String firstName, String lastName) {
        Map<String, Object> body = clubAdminUserBody(clubId, username, email, firstName, lastName);
        try {
            ResponseEntity<Void> response = http.post()
                    .uri(props.adminBase() + "/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            URI location = Objects.requireNonNull(response, "null response from users POST")
                    .getHeaders().getLocation();
            if (location != null) {
                return uuidFromLocation(location, "create club-admin user");
            }
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() != 409) {
                throw transportFailure("club-admin user create", e);
            }
            // Idempotent replay — a prior migrate already minted this user.
        }
        UUID existing = findUserIdByUsername(username);
        if (existing == null) {
            throw new KeycloakProvisioningException(
                    "club-admin user create returned no Location and post-race read missed "
                            + username);
        }
        return existing;
    }

    /**
     * Builds the {@code POST /admin/realms/{realm}/users} representation for
     * a migrated club admin. Extracted + package-private so the
     * adapter-boundary unit test can assert the wire shape carries
     * firstName/lastName (no real realm in the local test path).
     *
     * <p>firstName/lastName are load-bearing: the realm's declarative
     * user-profile marks both {@code required} for {@code user}-roled
     * accounts, so a blank name triggers {@code VERIFY_PROFILE} on first
     * login. Setting them at mint time keeps the migrated admin loginable in
     * one shot (J-1 T-06 — replaces the e2e {@code makeMigratedAdminLoginable}
     * name fixup).
     */
    static Map<String, Object> clubAdminUserBody(UUID clubId, String username, String email,
                                                 String firstName, String lastName) {
        return Map.of(
                "username", username,
                "email", email,
                "firstName", firstName,
                "lastName", lastName,
                "enabled", true,
                "emailVerified", false,
                "requiredActions", List.of("UPDATE_PASSWORD"),
                "attributes", Map.of(
                        KeycloakDeploymentNames.CLUB_ID_USER_ATTRIBUTE,
                        List.of(clubId.toString())));
    }

    private @Nullable UUID findUserIdByUsername(String username) {
        String uri = UriComponentsBuilder.fromUriString(props.adminBase() + "/users")
                .queryParam("username", username)
                .queryParam("exact", true)
                .queryParam("max", 2)
                .build()
                .toUriString();
        try {
            String responseBody = http.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);
            List<KeycloakNamedRef> matches = readListOf(responseBody);
            return matches.isEmpty() ? null : matches.get(0).id();
        } catch (HttpStatusCodeException e) {
            throw transportFailure("club-admin user lookup", e);
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
            throw transportFailure("group search", e);
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
            throw transportFailure("realm-role lookup", e);
        }
    }

    // The two JSON readers below intentionally strip the cause from
    // their thrown exception: the upstream Keycloak response body can
    // carry realm payload (audit messages, error contexts), and a
    // forensic log forwarder grepping `e.getCause()` would surface
    // that. Status code is preserved at the transport-failure boundary.

    private List<KeycloakNamedRef> readListOf(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    body,
                    objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, KeycloakNamedRef.class));
        } catch (Exception ignored) {
            throw new KeycloakProvisioningException("malformed JSON list from directory");
        }
    }

    private @Nullable KeycloakNamedRef readNamed(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(body, KeycloakNamedRef.class);
        } catch (Exception ignored) {
            throw new KeycloakProvisioningException("malformed JSON object from directory");
        }
    }

    /**
     * Wraps an upstream HTTP error in our own exception type with the
     * status only — never the response body, which could leak the realm
     * payload via cause chains in a forensic log forwarder.
     */
    private static KeycloakProvisioningException transportFailure(
            String action, HttpStatusCodeException source) {
        return new KeycloakProvisioningException(
                "directory " + action + " refused (status " + source.getStatusCode().value() + ")");
    }

    private static UUID uuidFromLocation(URI location, String action) {
        String path = location.getPath();
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash + 1 >= path.length()) {
            throw new KeycloakProvisioningException(
                    action + ": malformed Location " + path);
        }
        try {
            return UUID.fromString(path.substring(lastSlash + 1));
        } catch (IllegalArgumentException e) {
            throw new KeycloakProvisioningException(
                    action + ": Location tail is not a UUID — " + path);
        }
    }

    /**
     * Minimal projection over Keycloak's {id, name, ...} shape.
     *
     * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} is load-bearing:
     * the injected Spring {@code ObjectMapper} runs with
     * {@code spring.jackson.deserialization.fail-on-unknown-properties: true}
     * (strict DTO boundary, see application.yml). Keycloak's real responses
     * are verbose — a {@code RoleRepresentation} from {@code GET /roles/{name}}
     * carries {@code composite}, {@code clientRole}, {@code containerId},
     * {@code attributes}; a {@code UserRepresentation} / {@code GroupRepresentation}
     * carry many more. Without per-record opt-out, the very first such body
     * blows up as {@code UnrecognizedPropertyException} and the adapter
     * mislabels it "malformed JSON". Pin tolerance on the projection so the
     * directory contract is independent of the global wire-boundary policy.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record KeycloakNamedRef(@Nullable UUID id, @Nullable String name) {}

    /**
     * Adapter-local runtime exception. The reconcile job catches +
     * leaves {@code kc_state=PENDING}; the exception never reaches the
     * SPA (the reconcile is post-commit and not surfaced through an
     * advice).
     */
    static final class KeycloakProvisioningException extends RuntimeException {
        KeycloakProvisioningException(String message) {
            super(message);
        }
    }
}
