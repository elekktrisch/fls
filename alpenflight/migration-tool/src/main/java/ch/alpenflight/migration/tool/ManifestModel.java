package ch.alpenflight.migration.tool;

import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Jar-local serialization DTO for {@code manifest.json} — the bundle's first
 * tar entry. Deliberately field-for-field identical to the server's
 * {@code ch.alpenflight.migrations.application.BundleManifest} so the server
 * deserializes it without a tolerant-reader shim (its
 * {@code @JsonIgnoreProperties(ignoreUnknown = false)} rejects any extra
 * field). The jar cannot depend on the server module, so the shape is
 * re-declared here rather than imported.
 *
 * <p>{@code entityPolicies} reuses migration-bundle's {@link EntityPolicy}
 * (Jackson serializes it identically on both sides). The coverage gate the
 * server's {@code Manifest} enforces — every {@link EntityType} appears in
 * exactly one of {@code entityPolicies} / {@code unmappedReason} — is met by
 * {@link ManifestBuilder} before serialization.
 *
 * <p><strong>Per-entity-stream sha256 is intentionally NOT carried here.</strong>
 * The server's {@code BundleManifest} has no sha256 field and rejects unknown
 * fields, so emitting one would break ingest. The jar still computes per-entity
 * sha256 + row counts during the streaming write for {@code --verbose} stats
 * and the {@code --dry-run} report; they are surfaced to the operator, not
 * written into the manifest. See the S-139 report note.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "schemaVersion", "deploymentName", "clubs", "primaryClubId",
        "entityPolicies", "unmappedReason"})
public record ManifestModel(
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("deploymentName") String deploymentName,
        @JsonProperty("clubs") List<ClubDeclaration> clubs,
        @JsonProperty("primaryClubId") UUID primaryClubId,
        @JsonProperty("entityPolicies") Map<EntityType, EntityPolicy> entityPolicies,
        @JsonProperty("unmappedReason") Map<EntityType, String> unmappedReason) {

    /**
     * One Club declaration. Mirrors {@code BundleManifest.ClubDeclaration}.
     * The legacy GUID is preserved one-for-one (ADR 0019); the consumer keys
     * cross-Club FK resolution off it via {@code legacy_id_map_club}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({
            "legacyClubId", "name", "slug", "clubKey",
            "publicRegistrationEnabled", "countryId", "clubStateId"})
    public record ClubDeclaration(
            @JsonProperty("legacyClubId") UUID legacyClubId,
            @JsonProperty("name") String name,
            @JsonProperty("slug") String slug,
            @JsonProperty("clubKey") String clubKey,
            @JsonProperty("publicRegistrationEnabled") boolean publicRegistrationEnabled,
            @JsonProperty("countryId") UUID countryId,
            @JsonProperty("clubStateId") UUID clubStateId) {
    }
}
