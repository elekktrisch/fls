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
import java.util.Optional;
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

@Component
public class KeycloakAdminClient implements UserDirectoryPort {

    private static final List<UserDirectoryRow> NO_USERS_FOR_UNKNOWN_REALM_ROLE = List.of();
    private static final List<RealmRoleRef> NO_REALM_ROLES_FOR_MISSING_KC_IDENTITY = List.of();

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
    public void writeClubIdAttribute(UUID sub, UUID clubId) {
        Objects.requireNonNull(sub, "sub");
        Objects.requireNonNull(clubId, "clubId");
        UserMutableWire current = readUserForMerge(sub);
        Map<String, List<String>> attrs = new HashMap<>(current.attributesOrEmpty());
        attrs.put("clubId", List.of(clubId.toString()));
        putUserResendingFieldsKeycloakWouldClear(sub, current, attrs, "write clubId attribute");
    }

    @Override
    public void clearClubIdAttribute(UUID sub) {
        Objects.requireNonNull(sub, "sub");
        UserMutableWire current = readUserForMerge(sub);
        Map<String, List<String>> attrs = new HashMap<>(current.attributesOrEmpty());
        if (attrs.remove("clubId") == null) {
            return;
        }
        putUserResendingFieldsKeycloakWouldClear(sub, current, attrs, "clear clubId attribute");
    }

    private void putUserResendingFieldsKeycloakWouldClear(
            UUID sub, UserMutableWire current, Map<String, List<String>> attrs, String op) {
        Map<String, Object> body = new HashMap<>();
        if (current.username() != null) {
            body.put("username", current.username());
        }
        if (current.email() != null) {
            body.put("email", current.email());
        }
        if (current.firstName() != null) {
            body.put("firstName", current.firstName());
        }
        if (current.lastName() != null) {
            body.put("lastName", current.lastName());
        }
        if (current.enabled() != null) {
            body.put("enabled", current.enabled());
        }
        if (current.emailVerified() != null) {
            body.put("emailVerified", current.emailVerified());
        }
        if (current.requiredActions() != null) {
            body.put("requiredActions", current.requiredActions());
        }
        body.put("attributes", attrs);
        try {
            http.put()
                    .uri(props.adminBase() + "/users/" + sub)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            throw new UserDirectoryException(
                    "Keycloak " + op + " (status " + e.getStatusCode().value() + ")", e);
        }
    }

    private UserMutableWire readUserForMerge(UUID sub) {
        try {
            String body = http.get()
                    .uri(props.adminBase() + "/users/" + sub)
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                return UserMutableWire.empty();
            }
            return objectMapper.readValue(body, UserMutableWire.class);
        } catch (HttpStatusCodeException e) {
            throw new UserDirectoryException(
                    "Keycloak read user (status " + e.getStatusCode().value() + ")", e);
        } catch (Exception e) {
            throw new UserDirectoryException("Keycloak read user: malformed representation", e);
        }
    }

    @Override
    public Optional<DirectoryUser> findUserByEmailRealmWide(String email) {
        Objects.requireNonNull(email, "email");
        String uri = UriComponentsBuilder.fromUriString(props.adminBase() + "/users")
                .queryParam("email", email)
                .queryParam("exact", true)
                .queryParam("max", 1)
                .build()
                .toUriString();
        try {
            String body = http.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);
            return readListOf(body, UserLookupWire.class).stream()
                    .findFirst()
                    .map(UserLookupWire::toDirectoryUser);
        } catch (HttpStatusCodeException e) {
            throw new UserDirectoryException(
                    "Keycloak user-by-email lookup (status " + e.getStatusCode().value() + ")", e);
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
                return NO_USERS_FOR_UNKNOWN_REALM_ROLE;
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
                return NO_REALM_ROLES_FOR_MISSING_KC_IDENTITY;
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RealmRoleWire(@Nullable String id, String name, @Nullable String description) {
        RealmRoleRef toRef() {
            return new RealmRoleRef(id, name, description);
        }
    }

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserMutableWire(
            @Nullable String username,
            @Nullable String email,
            @Nullable String firstName,
            @Nullable String lastName,
            @Nullable Boolean enabled,
            @Nullable Boolean emailVerified,
            @Nullable List<String> requiredActions,
            @Nullable Map<String, List<String>> attributes) {
        static UserMutableWire empty() {
            return new UserMutableWire(null, null, null, null, null, null, null, null);
        }

        Map<String, List<String>> attributesOrEmpty() {
            return attributes == null ? Map.of() : attributes;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserLookupWire(UUID id, @Nullable Map<String, List<String>> attributes) {
        DirectoryUser toDirectoryUser() {
            return new DirectoryUser(id, clubIdFailClosed(), first("locale"));
        }

        private @Nullable UUID clubIdFailClosed() {
            if (attributes == null || !attributes.containsKey("clubId")) {
                return null;
            }
            String raw = first("clubId");
            if (raw == null || raw.isBlank()) {
                return DirectoryUser.CORRUPTED_CLUB_ID;
            }
            try {
                return UUID.fromString(raw);
            } catch (IllegalArgumentException e) {
                return DirectoryUser.CORRUPTED_CLUB_ID;
            }
        }

        private @Nullable String first(String key) {
            if (attributes == null) {
                return null;
            }
            List<String> values = attributes.get(key);
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }
}
