package ch.alpenflight.referencedata.application;

import ch.alpenflight.platform.id.AircraftStateId;
import ch.alpenflight.platform.id.AircraftTypeId;
import ch.alpenflight.platform.id.ClubStateId;
import ch.alpenflight.platform.id.CountryId;
import ch.alpenflight.platform.id.LocationTypeId;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

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

    @Schema(description = "LocationType listitem projection — dropdown fuel.")
    public record LocationTypeResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocationTypeId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isAirfield) {}

    @Schema(description = "AircraftType listitem projection — drives the type-discriminator dropdown + filter slices.")
    public record AircraftTypeResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AircraftTypeId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
            @Nullable Boolean hasEngine,
            @Nullable Boolean requiresTowingInfo,
            @Nullable Boolean mayBeTowingAircraft) {}

    @Schema(description = "AircraftState listitem projection — drives the airworthiness dropdown.")
    public record AircraftStateResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AircraftStateId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isAircraftFlyable) {}
}
