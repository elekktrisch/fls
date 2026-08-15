package ch.alpenflight.tenancy.provisioning.application;

import java.util.UUID;

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
