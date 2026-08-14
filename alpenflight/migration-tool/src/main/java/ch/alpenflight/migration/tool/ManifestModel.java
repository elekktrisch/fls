package ch.alpenflight.migration.tool;

import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
