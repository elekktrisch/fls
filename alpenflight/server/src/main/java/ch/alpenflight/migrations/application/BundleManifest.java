package ch.alpenflight.migrations.application;

import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@JsonIgnoreProperties(ignoreUnknown = false)
public record BundleManifest(
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("deploymentName") String deploymentName,
        @JsonProperty("clubs") List<ClubDeclaration> clubs,
        @JsonProperty("primaryClubId") @Nullable UUID primaryClubId,
        @JsonProperty("entityPolicies") Map<EntityType, EntityPolicy> entityPolicies,
        @JsonProperty("unmappedReason") Map<EntityType, String> unmappedReason) {

    @JsonCreator
    public BundleManifest {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        if (deploymentName == null || deploymentName.isBlank()) {
            throw new IllegalArgumentException("deploymentName must not be blank");
        }
        if (clubs == null) {
            throw new IllegalArgumentException("clubs must not be null");
        }
        clubs = List.copyOf(clubs);
        entityPolicies = entityPolicies == null ? Map.of() : Map.copyOf(entityPolicies);
        unmappedReason = unmappedReason == null ? Map.of() : Map.copyOf(unmappedReason);
    }

    public record ClubDeclaration(
            @JsonProperty("legacyClubId") UUID legacyClubId,
            @JsonProperty("name") String name,
            @JsonProperty("slug") String slug,
            @JsonProperty("clubKey") String clubKey,
            @JsonProperty("publicRegistrationEnabled") boolean publicRegistrationEnabled,
            @JsonProperty("countryId") UUID countryId,
            @JsonProperty("clubStateId") UUID clubStateId) {

        @JsonCreator
        public ClubDeclaration {
            if (legacyClubId == null) {
                throw new IllegalArgumentException("legacyClubId must not be null");
            }
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
}
