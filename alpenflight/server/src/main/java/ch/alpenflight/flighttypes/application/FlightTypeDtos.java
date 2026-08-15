package ch.alpenflight.flighttypes.application;

import ch.alpenflight.platform.id.FlightTypeId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

public final class FlightTypeDtos {

    private FlightTypeDtos() {}

    @Schema(description = "FlightType detail projection — full payload.")
    public record FlightTypeDetail(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FlightTypeId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String flightTypeName,
            @Nullable String flightCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isInstructorRequired,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isObserverPilotOrInstructorRequired,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isCheckFlight,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isPassengerFlight,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isSoloFlight,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isForGliderFlights,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isForTowFlights,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isForMotorFlights,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isFlightCostBalanceSelectable,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isCouponNumberRequired,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isForAircraftReservationType,
            @Nullable Integer minNrOfAircraftSeatsRequired) {}

    @Schema(description = "FlightType listitem projection — sorted by name.")
    public record FlightTypeListItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FlightTypeId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String flightTypeName,
            @Nullable String flightCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isForGliderFlights,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isForTowFlights,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isForMotorFlights,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isFlightCostBalanceSelectable) {}

    @Schema(description = "Payload to register a new FlightType in the caller's tenant.")
    public record FlightTypeCreateRequest(
            @NotBlank @Size(max = 100) String flightTypeName,
            @Nullable @Size(max = 30) String flightCode,
            boolean isInstructorRequired,
            boolean isObserverPilotOrInstructorRequired,
            boolean isCheckFlight,
            boolean isPassengerFlight,
            boolean isSoloFlight,
            boolean isForGliderFlights,
            boolean isForTowFlights,
            boolean isForMotorFlights,
            boolean isFlightCostBalanceSelectable,
            boolean isCouponNumberRequired,
            boolean isForAircraftReservationType,
            @Nullable @Min(value = 1, message = "minNrOfAircraftSeatsRequired must be >= 1")
                    Integer minNrOfAircraftSeatsRequired) {}

    @Schema(description = "Payload to update a FlightType. operating_club_id is not settable here (A04 defense).")
    public record FlightTypeUpdateRequest(
            @NotBlank @Size(max = 100) String flightTypeName,
            @Nullable @Size(max = 30) String flightCode,
            boolean isInstructorRequired,
            boolean isObserverPilotOrInstructorRequired,
            boolean isCheckFlight,
            boolean isPassengerFlight,
            boolean isSoloFlight,
            boolean isForGliderFlights,
            boolean isForTowFlights,
            boolean isForMotorFlights,
            boolean isFlightCostBalanceSelectable,
            boolean isCouponNumberRequired,
            boolean isForAircraftReservationType,
            @Nullable @Min(value = 1, message = "minNrOfAircraftSeatsRequired must be >= 1")
                    Integer minNrOfAircraftSeatsRequired) {}
}
