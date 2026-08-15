package ch.alpenflight.locations.application;

import ch.alpenflight.platform.id.CountryId;
import ch.alpenflight.platform.id.InOutboundPointId;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.LocationTypeId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public final class LocationDtos {

    private LocationDtos() {}

    static final String ICAO_REGEX = "^[A-Z]{4}$|^[A-Z]{2}[0-9]{2}$";

    @Schema(description = "Location listitem projection — sort_indicator + name ordered.")
    public record LocationListItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocationId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String locationName,
            @Nullable String locationShortName,
            @Nullable String icaoCode,
            @Nullable String locationTypeCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isAirfield,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isFastEntryRecord) {}

    @Schema(description = "InOutboundPoint projection — child of a Location.")
    public record InOutboundPointResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) InOutboundPointId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String pointName,
            @Nullable String pointType,
            @Nullable String direction,
            @Nullable String description) {}

    @Schema(description = "Location detail projection — full payload + nested IOPs.")
    public record LocationDetail(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocationId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String locationName,
            @Nullable String locationShortName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CountryId countryId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocationTypeId locationTypeId,
            @Nullable String icaoCode,
            @Nullable String latitude,
            @Nullable String longitude,
            @Nullable Integer elevation,
            @Nullable UUID elevationUnitTypeId,
            @Nullable String runwayDirection,
            @Nullable Integer runwayLength,
            @Nullable UUID runwayLengthUnitTypeId,
            @Nullable String airportFrequency,
            @Nullable String description,
            @Nullable Integer sortIndicator,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isInboundRouteRequired,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isOutboundRouteRequired,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isFastEntryRecord,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
                    List<InOutboundPointResponse> inOutboundPoints) {}

    @Schema(description = "Payload to create a Location. ICAO code is optional but uppercase-regex bound when present.")
    public record LocationCreateRequest(
            @NotBlank @Size(max = 100) String locationName,
            @Nullable @Size(max = 50) String locationShortName,
            @NotNull CountryId countryId,
            @NotNull LocationTypeId locationTypeId,
            @Nullable @Size(max = 10) @Pattern(regexp = ICAO_REGEX) String icaoCode,
            @Nullable @Size(max = 10) String latitude,
            @Nullable @Size(max = 10) String longitude,
            @Nullable Integer elevation,
            @Nullable UUID elevationUnitTypeId,
            @Nullable @Size(max = 50) String runwayDirection,
            @Nullable Integer runwayLength,
            @Nullable UUID runwayLengthUnitTypeId,
            @Nullable @Size(max = 50) String airportFrequency,
            @Nullable String description,
            @Nullable Integer sortIndicator,
            boolean isInboundRouteRequired,
            boolean isOutboundRouteRequired,
            boolean isFastEntryRecord,
            @Nullable @Valid List<InOutboundPointRequest> inOutboundPoints) {}

    @Schema(description = "Payload to update a Location. Replaces the full IOP list (orphan-removal cleans up).")
    public record LocationUpdateRequest(
            @NotBlank @Size(max = 100) String locationName,
            @Nullable @Size(max = 50) String locationShortName,
            @NotNull CountryId countryId,
            @NotNull LocationTypeId locationTypeId,
            @Nullable @Size(max = 10) @Pattern(regexp = ICAO_REGEX) String icaoCode,
            @Nullable @Size(max = 10) String latitude,
            @Nullable @Size(max = 10) String longitude,
            @Nullable Integer elevation,
            @Nullable UUID elevationUnitTypeId,
            @Nullable @Size(max = 50) String runwayDirection,
            @Nullable Integer runwayLength,
            @Nullable UUID runwayLengthUnitTypeId,
            @Nullable @Size(max = 50) String airportFrequency,
            @Nullable String description,
            @Nullable Integer sortIndicator,
            boolean isInboundRouteRequired,
            boolean isOutboundRouteRequired,
            boolean isFastEntryRecord,
            @Nullable @Valid List<InOutboundPointRequest> inOutboundPoints) {}

    @Schema(description = "Payload to create / replace an InOutboundPoint under a Location.")
    public record InOutboundPointRequest(
            @NotBlank @Size(max = 100) String pointName,
            @Nullable @Size(max = 50) String pointType,
            @Nullable @Size(max = 50) String direction,
            @Nullable String description) {}
}
