package ch.alpenflight.flighttypes.application;

import ch.alpenflight.flighttypes.application.FlightCostBalanceTypeDtos.FlightCostBalanceTypeResponse;
import ch.alpenflight.flighttypes.application.FlightTypeDtos.FlightTypeDetail;
import ch.alpenflight.flighttypes.application.FlightTypeDtos.FlightTypeListItem;
import ch.alpenflight.flighttypes.domain.FlightCostBalanceType;
import ch.alpenflight.flighttypes.domain.FlightType;
import ch.alpenflight.platform.id.FlightCostBalanceTypeId;
import ch.alpenflight.platform.id.FlightTypeId;
import java.util.Objects;

final class FlightTypeMapper {

    private FlightTypeMapper() {}

    static FlightTypeDetail toDetail(FlightType ft) {
        FlightTypeId id = Objects.requireNonNull(ft.getId(),
                "FlightType.id must be non-null after persist");
        return new FlightTypeDetail(
                id,
                ft.getFlightTypeName(),
                ft.getFlightCode(),
                ft.isInstructorRequired(),
                ft.isObserverPilotOrInstructorRequired(),
                ft.isCheckFlight(),
                ft.isPassengerFlight(),
                ft.isSoloFlight(),
                ft.isForGliderFlights(),
                ft.isForTowFlights(),
                ft.isForMotorFlights(),
                ft.isFlightCostBalanceSelectable(),
                ft.isCouponNumberRequired(),
                ft.isForAircraftReservationType(),
                ft.getMinNrOfAircraftSeatsRequired());
    }

    static FlightTypeListItem toListItem(FlightType ft) {
        FlightTypeId id = Objects.requireNonNull(ft.getId(),
                "FlightType.id must be non-null after persist");
        return new FlightTypeListItem(
                id,
                ft.getFlightTypeName(),
                ft.getFlightCode(),
                ft.isForGliderFlights(),
                ft.isForTowFlights(),
                ft.isForMotorFlights(),
                ft.isFlightCostBalanceSelectable());
    }

    static FlightCostBalanceTypeResponse toResponse(FlightCostBalanceType fcb) {
        FlightCostBalanceTypeId id = Objects.requireNonNull(fcb.getId(),
                "FlightCostBalanceType.id must be non-null");
        return new FlightCostBalanceTypeResponse(
                id,
                fcb.getCode(),
                fcb.getDescription(),
                fcb.isPersonForInvoiceRequired(),
                fcb.isForGlider(),
                fcb.isForTow(),
                fcb.isForMotor());
    }
}
