package ch.alpenflight.users.infra.keycloak;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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
 * Typed Spring-{@code RestClient} façade over the Keycloak admin REST API.
 *
 * <p>Surface is intentionally narrow — only the calls S-052 + S-028 need.
 * Every method either returns a typed record or throws
 * {@link KeycloakAdminException} carrying the HTTP status (never the upstream
 * response body, see exception docs).
 *
 * <p><strong>Tenant scoping.</strong> {@link #findUsersInClub} appends
 * {@code q=clubId:<clubId>}; an empty {@code clubId} would search realm-wide,
 * which is the R1-shape leak the security plan calls out — IT pins the
 * behavior with a wire assertion.
 *
 * <p><strong>Logging.</strong> A {@link RedactingRestClientInterceptor}
 * scrubs the {@code Authorization} header from any debug log output so the
 * admin token never lands in request logs.
 */
@Component
public class KeycloakAdminClient {

    private final RestClient http;
    private final KeycloakAdminProperties props;
    private final ObjectMapper objectMapper;

    public KeycloakAdminClient(RestClient.Builder builder,
                               KeycloakAdminTokenSupplier tokens,
                               KeycloakAdminProperties props,
                               ObjectMapper objectMapper) {
        this.http = builder
                .requestInterceptor(new BearerTokenInterceptor(tokens))
                .requestInterceptor(RedactingRestClientInterceptor.INSTANCE)
                .build();
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * Create a new Keycloak user from {@link KcUserSpec}. Returns the
     * server-assigned {@code sub} (extracted from the {@code Location}
     * response header). The {@code Location} parse is by-suffix so the
     * Keycloak host swap (compose-internal {@code keycloak:8080} vs.
     * host-pinned {@code localhost:8090}) doesn't bite.
     */
    public UUID createUser(KcUserSpec spec) {
        ResponseEntity<Void> response;
        try {
            response = http.post()
                    .uri(props.adminBase() + "/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(spec.toCreatePayload())
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 409) {
                throw new KeycloakAdminException(
                        "Keycloak rejected user create: username or email already exists");
            }
            throw new KeycloakAdminException(
                    "Keycloak refused user create (status " + status.value() + ")", e);
        }
        Objects.requireNonNull(response, "Keycloak admin call returned null entity");
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new KeycloakAdminException("Keycloak user create: missing Location header");
        }
        String path = location.getPath();
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash + 1 >= path.length()) {
            throw new KeycloakAdminException("Keycloak user create: malformed Location " + path);
        }
        try {
            return UUID.fromString(path.substring(lastSlash + 1));
        } catch (IllegalArgumentException e) {
            throw new KeycloakAdminException(
                    "Keycloak user create: Location tail is not a UUID — " + path, e);
        }
    }

    /**
     * Delete a Keycloak user. Used as a compensating action on local-row
     * insert failure (see {@code UsersService.invite}). 404 is treated as
     * success — the orphan was already gone.
     */
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
            throw new KeycloakAdminException(
                    "Keycloak user delete (status " + e.getStatusCode().value() + ")", e);
        }
    }

    /**
     * Flip the Keycloak user's {@code enabled} flag. Soft-delete pairs the
     * local {@code deleted_on} with {@code enabled=false} here — we never
     * call {@link #deleteUser} for the operator-visible delete path.
     */
    public void setEnabled(UUID sub, boolean enabled) {
        try {
            http.put()
                    .uri(props.adminBase() + "/users/" + sub)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("enabled", enabled))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            throw new KeycloakAdminException(
                    "Keycloak setEnabled (status " + e.getStatusCode().value() + ")", e);
        }
    }

    /**
     * List users in a club. Forces {@code q=clubId:<clubId>}; an empty
     * {@code clubId} would search realm-wide and is rejected up-front.
     */
    public List<KcUserRow> findUsersInClub(UUID clubId, int max) {
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
            return readListOf(body, KcUserRow.class);
        } catch (HttpStatusCodeException e) {
            throw new KeycloakAdminException(
                    "Keycloak users list (status " + e.getStatusCode().value() + ")", e);
        }
    }

    /** Read the realm role-mappings for one user. */
    public List<KcRealmRoleRef> getRealmRoleMappings(UUID sub) {
        try {
            String body = http.get()
                    .uri(props.adminBase() + "/users/" + sub + "/role-mappings/realm")
                    .retrieve()
                    .body(String.class);
            return readListOf(body, KcRealmRoleRef.class);
        } catch (HttpStatusCodeException e) {
            throw new KeycloakAdminException(
                    "Keycloak read role-mappings (status " + e.getStatusCode().value() + ")", e);
        }
    }

    /**
     * Read available realm roles (the full catalogue) and project to the
     * subset the application caller is allowed to see. The caller normally
     * passes the application's role names; the realm-role objects carry
     * the {@code id} field Keycloak requires on grant calls.
     */
    public List<KcRealmRoleRef> findRealmRolesByName(Set<String> names) {
        if (names.isEmpty()) {
            return List.of();
        }
        try {
            String body = http.get()
                    .uri(props.adminBase() + "/roles")
                    .retrieve()
                    .body(String.class);
            List<KcRealmRoleRef> all = readListOf(body, KcRealmRoleRef.class);
            return all.stream().filter(r -> names.contains(r.name())).toList();
        } catch (HttpStatusCodeException e) {
            throw new KeycloakAdminException(
                    "Keycloak list realm roles (status " + e.getStatusCode().value() + ")", e);
        }
    }

    public void grantRealmRoles(UUID sub, List<KcRealmRoleRef> roles) {
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
            throw new KeycloakAdminException(
                    "Keycloak role-mappings grant (status " + e.getStatusCode().value() + ")", e);
        }
    }

    public void revokeRealmRoles(UUID sub, List<KcRealmRoleRef> roles) {
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
            throw new KeycloakAdminException(
                    "Keycloak role-mappings revoke (status " + e.getStatusCode().value() + ")", e);
        }
    }

    /**
     * Fire {@code executeActionsEmail} for {@code UPDATE_PASSWORD}. Used by
     * invite + resend-invite. Best-effort: the caller surfaces the result
     * but doesn't roll the business transaction back on email failure.
     */
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
            throw new KeycloakAdminException(
                    "Keycloak execute-actions-email (status " + e.getStatusCode().value() + ")", e);
        }
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
            // Don't echo the body — it may carry user payloads.
            throw new KeycloakAdminException("Keycloak admin: malformed JSON list for " + type.getSimpleName(), e);
        }
    }

    /** Payload for {@code createUser}. */
    public record KcUserSpec(
            String username,
            String email,
            String firstName,
            String lastName,
            UUID clubId,
            @Nullable String locale,
            List<String> requiredActions,
            boolean enabled) {

        public Map<String, Object> toCreatePayload() {
            Map<String, Object> body = new HashMap<>();
            body.put("username", username);
            body.put("email", email);
            body.put("firstName", firstName == null ? "" : firstName);
            body.put("lastName", lastName == null ? "" : lastName);
            body.put("enabled", enabled);
            body.put("emailVerified", false);
            body.put("requiredActions", requiredActions);
            Map<String, List<String>> attrs = new HashMap<>();
            attrs.put("clubId", List.of(clubId.toString()));
            if (locale != null && !locale.isBlank()) {
                attrs.put("locale", List.of(locale.toLowerCase(Locale.ROOT)));
            }
            body.put("attributes", attrs);
            return body;
        }
    }

    /** Lightweight projection of a {@code UserRepresentation} row. */
    public record KcUserRow(
            UUID id,
            @Nullable String username,
            @Nullable String email,
            @Nullable Boolean enabled,
            @Nullable List<String> requiredActions,
            @Nullable Long createdTimestamp) {}

    /** Realm-role reference shape Keycloak's admin API expects for grant / revoke. */
    public record KcRealmRoleRef(
            @Nullable String id,
            String name,
            @Nullable String description) {}
}
