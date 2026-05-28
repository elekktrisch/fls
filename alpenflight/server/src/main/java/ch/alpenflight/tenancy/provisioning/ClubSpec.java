package ch.alpenflight.tenancy.provisioning;

import java.util.UUID;

/**
 * One Club from the bundle manifest. The mapper that builds this from
 * {@code bundle.json} strips any inbound {@code deployment_id} field — see
 * security plan in S-138 (defends against a malicious bundle smuggling
 * a Club into another user's Deployment).
 */
public record ClubSpec(
        String name,
        String slug,
        String clubKey,
        boolean publicRegistrationEnabled,
        UUID countryId,
        UUID clubStateId) {

    public ClubSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug must not be blank");
        }
        if (clubKey == null || clubKey.isBlank()) {
            throw new IllegalArgumentException("clubKey must not be blank");
        }
        if (countryId == null) {
            throw new IllegalArgumentException("countryId must not be null");
        }
        if (clubStateId == null) {
            throw new IllegalArgumentException("clubStateId must not be null");
        }
    }
}
