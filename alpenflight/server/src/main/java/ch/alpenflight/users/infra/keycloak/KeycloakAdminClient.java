package ch.alpenflight.users.infra.keycloak;

import ch.alpenflight.platform.keycloak.BearerTokenInterceptor;
import ch.alpenflight.platform.keycloak.KeycloakAdminProperties;
import ch.alpenflight.platform.keycloak.KeycloakAdminTokenSupplier;
import ch.alpenflight.platform.keycloak.RedactingRestClientInterceptor;
import ch.alpenflight.users.domain.UserDirectoryException;
import ch.alpenflight.users.domain.UserDirectoryPort;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.URI;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Keycloak adapter for {@link UserDirectoryPort}. Uses Spring's
 * {@link RestClient} (synchronous, no WebFlux dependency).
 *
 * <p>Surface is intentionally narrow — only the calls S-052 + S-028 need.
 * Every method either returns a typed port record or throws
 * {@link UserDirectoryException} carrying the HTTP status (never the
 * upstream response body, see exception docs).
 *
 * <p><strong>Tenant scoping.</strong> {@link #findUsersInClub} appends
 * {@code q=clubId:<clubId>}; an empty {@code clubId} would search realm-
 * wide, which is the R1-shape leak the security plan calls out.
 *
 * <p><strong>Logging.</strong> The bearer-token + redaction interceptors
 * mean the admin token never lands in request logs.
 */
@Component
public class KeycloakAdminClient implements UserDirectoryPort {

    private final RestClient http;
    private final KeycloakAdminProperties props;
    private final ObjectMapper objectMapper;

    public KeycloakAdminClient(KeycloakAdminTokenSupplier tokens,
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
    public UUID createUser(UserDirectorySpec spec) {
        ResponseEntity<Void> response;
        try {
            response = http.post()
                    .uri(props.adminBase() + "/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toCreatePayload(spec))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 409) {
                throw new UserDirectoryException(
                        "Keycloak rejected user create: username or email already exists");
            }
            throw new UserDirectoryException(
                    "Keycloak refused user create (status " + status.value() + ")", e);
        }
        Objects.requireNonNull(response, "Keycloak admin call returned null entity");
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new UserDirectoryException("Keycloak user create: missing Location header");
        }
        String path = location.getPath();
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash + 1 >= path.length()) {
            throw new UserDirectoryException("Keycloak user create: malformed Location " + path);
        }
        try {
            return UUID.fromString(path.substring(lastSlash + 1));
        } catch (IllegalArgumentException e) {
            throw new UserDirectoryException(
                    "Keycloak user create: Location tail is not a UUID — " + path, e);
        }
    }

    @Override
    public void deleteUser(UUID sub) {
        try {
            http.delete()
                    .uri(props.adminBase() + "/users/" + sub)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 404) {
                return;
            }
            throw new UserDirectoryException(
                    "Keycloak user delete (status " + e.getStatusCode().value() + ")", e);
        }
    }

    @Override
    public void setEnabled(UUID sub, boolean enabled) {
        try {
            http.put()
                    .uri(props.adminBase() + "/users/" + sub)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("enabled", enabled))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            throw new UserDirectoryException(
                    "Keycloak setEnabled (status " + e.getStatusCode().value() + ")", e);
        }
    }

    @Override
    public List<UserDirectoryRow> findUsersInClub(UUID clubId, int max) {
        Objects.requireNonNull(clubId, "clubId");
        String uri = UriComponentsBuilder.fromUriString(props.adminBase() + "/users")
                .queryParam("q", "clubId:" + clubId)
                .queryParam("max", max)
                .build()
                .toUriString();
        try {
            String body = http.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);
            return readListOf(body, UserWire.class).stream().map(UserWire::toRow).toList();
        } catch (HttpStatusCodeException e) {
            throw new UserDirectoryException(
                    "Keycloak users list (status " + e.getStatusCode().value() + ")", e);
        }
    }

    @Override
    public List<UserDirectoryRow> findUsersByRoleName(String roleName) {
        try {
            String body = http.get()
                    .uri(props.adminBase() + "/roles/" + roleName + "/users")
                    .retrieve()
                    .body(String.class);
            return readListOf(body, UserWire.class).stream().map(UserWire::toRow).toList();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 404) {
                // Role unknown in this realm → no members. Treat as empty.
                return List.of();
            }
            throw new UserDirectoryException(
                    "Keycloak users-by-role (status " + e.getStatusCode().value() + ")", e);
        }
    }

    @Override
    public List<RealmRoleRef> getRealmRoleMappings(UUID sub) {
        try {
            String body = http.get()
                    .uri(props.adminBase() + "/users/" + sub + "/role-mappings/realm")
                    .retrieve()
                    .body(String.class);
            return readListOf(body, RealmRoleWire.class).stream().map(RealmRoleWire::toRef).toList();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 404) {
                // The KC identity for this sub is gone (deleted in KC, or a local
                // t_user row whose keycloak_sub was never provisioned in the
                // realm — e.g. a seed / bulk-import row). A user with no KC
                // identity has no realm roles; treat as the empty mapping rather
                // than failing. Without this, ONE orphaned row in the club blows
                // up the whole GET /api/v1/users list (J-26 T-30c: 502 when the
                // KC admin client is reachable, 400 when it is not — both the
                // same single-row-not-found root cause). Mirrors
                // findUsersByRoleName's 404 → empty handling.
                return List.of();
            }
            throw new UserDirectoryException(
                    "Keycloak read role-mappings (status " + e.getStatusCode().value() + ")", e);
        }
    }

    @Override
    public List<RealmRoleRef> findRealmRolesByName(Set<String> names) {
        if (names.isEmpty()) {
            return List.of();
        }
        try {
            String body = http.get()
                    .uri(props.adminBase() + "/roles")
                    .retrieve()
                    .body(String.class);
            return readListOf(body, RealmRoleWire.class).stream()
                    .filter(r -> names.contains(r.name()))
                    .map(RealmRoleWire::toRef)
                    .toList();
        } catch (HttpStatusCodeException e) {
            throw new UserDirectoryException(
                    "Keycloak list realm roles (status " + e.getStatusCode().value() + ")", e);
        }
    }

    @Override
    public void grantRealmRoles(UUID sub, List<RealmRoleRef> roles) {
        if (roles.isEmpty()) {
            return;
        }
        try {
            http.post()
                    .uri(props.adminBase() + "/users/" + sub + "/role-mappings/realm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(roles)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            throw new UserDirectoryException(
                    "Keycloak role-mappings grant (status " + e.getStatusCode().value() + ")", e);
        }
    }

    @Override
    public void revokeRealmRoles(UUID sub, List<RealmRoleRef> roles) {
        if (roles.isEmpty()) {
            return;
        }
        try {
            http.method(org.springframework.http.HttpMethod.DELETE)
                    .uri(props.adminBase() + "/users/" + sub + "/role-mappings/realm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(roles)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            throw new UserDirectoryException(
                    "Keycloak role-mappings revoke (status " + e.getStatusCode().value() + ")", e);
        }
    }

    @Override
    public void sendExecuteActions(UUID sub, List<String> actions, Duration lifespan) {
        String uri = UriComponentsBuilder.fromUriString(
                        props.adminBase() + "/users/" + sub + "/execute-actions-email")
                .queryParam("lifespan", lifespan.toSeconds())
                .build()
                .toUriString();
        try {
            http.put()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(actions)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            throw new UserDirectoryException(
                    "Keycloak execute-actions-email (status " + e.getStatusCode().value() + ")", e);
        }
    }

    private static Map<String, Object> toCreatePayload(UserDirectorySpec spec) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", spec.username());
        body.put("email", spec.email());
        body.put("firstName", spec.firstName() == null ? "" : spec.firstName());
        body.put("lastName", spec.lastName() == null ? "" : spec.lastName());
        body.put("enabled", spec.enabled());
        body.put("emailVerified", false);
        body.put("requiredActions", spec.requiredActions());
        Map<String, List<String>> attrs = new HashMap<>();
        attrs.put("clubId", List.of(spec.clubId().toString()));
        if (spec.locale() != null && !spec.locale().isBlank()) {
            attrs.put("locale", List.of(spec.locale().toLowerCase(Locale.ROOT)));
        }
        body.put("attributes", attrs);
        return body;
    }

    private <T> List<T> readListOf(@Nullable String body, Class<T> type) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    body,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, type));
        } catch (Exception e) {
            throw new UserDirectoryException(
                    "Keycloak admin: malformed JSON list for " + type.getSimpleName(), e);
        }
    }

    /**
     * Adapter-local wire projection over Keycloak's {@code RoleRepresentation}.
     *
     * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} is load-bearing:
     * the injected Spring {@code ObjectMapper} runs with
     * {@code spring.jackson.deserialization.fail-on-unknown-properties: true}
     * (strict DTO boundary, see {@code application.yml}). Keycloak 26.5.7's real
     * {@code GET /users/{id}/role-mappings/realm} returns an ARRAY of full
     * {@code RoleRepresentation} objects carrying {@code description},
     * {@code composite}, {@code clientRole}, {@code containerId},
     * {@code attributes}. Deserialising those straight into the annotation-free
     * domain {@link RealmRoleRef} blew up as {@code UnrecognizedPropertyException}
     * and the adapter mislabelled it "malformed JSON list for RealmRoleRef" → 502
     * (J-6b §4 gate; operator #7 "Users menu 400"). Pin tolerance on the wire DTO
     * so the directory contract is independent of the global wire-boundary policy
     * and the domain port stays Jackson-free (see {@link UserDirectoryPort}).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RealmRoleWire(@Nullable String id, String name, @Nullable String description) {
        RealmRoleRef toRef() {
            return new RealmRoleRef(id, name, description);
        }
    }

    /**
     * Adapter-local wire projection over Keycloak's {@code UserRepresentation}.
     * Same rationale as {@link RealmRoleWire}: KC user-list responses carry far
     * more than {id, username, email, enabled, requiredActions, createdTimestamp}
     * ({@code totp}, {@code disableableCredentialTypes}, {@code notBefore},
     * {@code access}, {@code attributes}, …). Tolerate the extras here so a future
     * KC field add can't re-trip the same latent 502 on the users list.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserWire(
            UUID id,
            @Nullable String username,
            @Nullable String email,
            @Nullable Boolean enabled,
            @Nullable List<String> requiredActions,
            @Nullable Long createdTimestamp) {
        UserDirectoryRow toRow() {
            return new UserDirectoryRow(id, username, email, enabled, requiredActions, createdTimestamp);
        }
    }
}
