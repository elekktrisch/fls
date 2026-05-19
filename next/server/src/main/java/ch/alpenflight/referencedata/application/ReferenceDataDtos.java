package ch.alpenflight.referencedata.application;

import ch.alpenflight.platform.id.ClubStateId;
import ch.alpenflight.platform.id.CountryId;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Listitem projections for the reference-data REST surface. Records;
 * read-only; no audit metadata exposed.
 */
public final class ReferenceDataDtos {

    private ReferenceDataDtos() {}

    @Schema(description = "Country listitem projection — dropdown fuel.")
    public record CountryResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CountryId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String iso2Code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name) {}

    @Schema(description = "ClubState listitem projection — dropdown fuel.")
    public record ClubStateResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ClubStateId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name) {}
}
