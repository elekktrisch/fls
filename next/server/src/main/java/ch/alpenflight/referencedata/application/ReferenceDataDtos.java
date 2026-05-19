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
            CountryId id,
            String iso2Code,
            String name) {}

    @Schema(description = "ClubState listitem projection — dropdown fuel.")
    public record ClubStateResponse(
            ClubStateId id,
            String code,
            String name) {}
}
