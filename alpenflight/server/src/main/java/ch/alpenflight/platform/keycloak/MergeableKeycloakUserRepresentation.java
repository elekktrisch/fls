package ch.alpenflight.platform.keycloak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MergeableKeycloakUserRepresentation(
        @Nullable String username,
        @Nullable String email,
        @Nullable String firstName,
        @Nullable String lastName,
        @Nullable Boolean enabled,
        @Nullable Boolean emailVerified,
        @Nullable List<String> requiredActions,
        @Nullable Map<String, List<String>> attributes) {

    public static MergeableKeycloakUserRepresentation empty() {
        return new MergeableKeycloakUserRepresentation(
                null, null, null, null, null, null, null, null);
    }

    public Map<String, List<String>> attributesOrEmpty() {
        return attributes == null ? Map.of() : attributes;
    }

    public Map<String, List<String>> attributesWithMerged(
            String attributeName, List<String> values) {
        Map<String, List<String>> merged = new HashMap<>(attributesOrEmpty());
        merged.put(attributeName, values);
        return merged;
    }

    public Map<String, Object> putBodyResendingFieldsKeycloakWouldClear(
            Map<String, List<String>> mergedAttributes) {
        Map<String, Object> body = new HashMap<>();
        if (username != null) {
            body.put("username", username);
        }
        if (email != null) {
            body.put("email", email);
        }
        if (firstName != null) {
            body.put("firstName", firstName);
        }
        if (lastName != null) {
            body.put("lastName", lastName);
        }
        if (enabled != null) {
            body.put("enabled", enabled);
        }
        if (emailVerified != null) {
            body.put("emailVerified", emailVerified);
        }
        if (requiredActions != null) {
            body.put("requiredActions", requiredActions);
        }
        body.put("attributes", mergedAttributes);
        return body;
    }
}
