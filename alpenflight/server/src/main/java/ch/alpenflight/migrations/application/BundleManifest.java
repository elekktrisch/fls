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

/**
 * S-141 bundle-envelope manifest. Materialised from the first tar entry
 * ({@code manifest.json}) before any entity stream is opened — a malformed
 * manifest fails-fast pre-COPY.
 *
 * <p>Sits alongside the migration-bundle module's {@link
 * ch.alpenflight.migration.bundle.Manifest} (the entity-policy carrier):
 * the two are coupled but split because the bundle module exists also for
 * the producer (S-139) where Deployment / Club metadata is meaningless.
 * The wire JSON is one document; the server re-binds {@code entityPolicies}
 * / {@code unmappedReason} through the bundle module's
 * {@code Manifest} constructor (allow-list validation) before any ingest.
 *
 * @param schemaVersion   {@link ch.alpenflight.migration.bundle.Manifest#CURRENT_SCHEMA_VERSION}
 *                        on the current wire format.
 * @param deploymentName  human-readable name for the Deployment-to-be.
 * @param clubs           one entry per Club the bundle carries (1..N).
 *                        S-138 provisioning requires ≥ 1.
 * @param primaryClubId   manifest hint for {@code t_deployment.primary_club_id}.
 *                        Resolves to the lowest UUID across {@code clubs} if absent.
 * @param entityPolicies  raw entity policies — passed to {@code Manifest}
 *                        for allow-list validation.
 * @param unmappedReason  per-entity reason an entity is intentionally absent.
 */
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
        // Empty clubs lists past the record boundary so the orchestrator
        // can surface the distinct 400 MANIFEST_EMPTY_CLUBS (not the catch-
        // all MANIFEST_INVALID Jackson would emit on record construction).
        clubs = List.copyOf(clubs);
        entityPolicies = entityPolicies == null ? Map.of() : Map.copyOf(entityPolicies);
        unmappedReason = unmappedReason == null ? Map.of() : Map.copyOf(unmappedReason);
    }

    /**
     * One Club from the bundle envelope. The {@code legacyClubId} is the
     * Postgres-side {@code legacy_id_map_club.legacy_guid} for cross-Club
     * FK resolution; producer + consumer agree the legacy GUID is preserved
     * one-for-one (ADR 0019 default cutover option).
     */
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
