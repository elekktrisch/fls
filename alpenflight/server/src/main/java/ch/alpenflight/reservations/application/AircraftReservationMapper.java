package ch.alpenflight.reservations.application;

import ch.alpenflight.platform.id.AircraftId;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.PersonId;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationDetail;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationListItem;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationTypeListItem;
import ch.alpenflight.reservations.domain.AircraftReservation;
import ch.alpenflight.reservations.domain.AircraftReservationRepository.ListItemRow;
import ch.alpenflight.reservations.domain.AircraftReservationRepository.ListRow;
import ch.alpenflight.reservations.domain.AircraftReservationRepository.TypeListItem;
import java.util.Objects;

final class AircraftReservationMapper {

    private AircraftReservationMapper() {}

    static AircraftReservationDetail toDetail(AircraftReservation r) {
        return new AircraftReservationDetail(
                Objects.requireNonNull(r.getId(), "Cannot map an unpersisted AircraftReservation"),
                Objects.requireNonNull(r.getOperatingClubId(),
                        "AircraftReservation is missing operatingClubId (@TenantId NOT NULL)"),
                AircraftId.of(Objects.requireNonNull(r.getAircraftId(),
                        "AircraftReservation is missing aircraftId")),
                PersonId.of(Objects.requireNonNull(r.getPilotPersonId(),
                        "AircraftReservation is missing pilotPersonId")),
                PersonId.ofNullable(r.getSecondCrewPersonId()),
                LocationId.of(Objects.requireNonNull(r.getLocationId(),
                        "AircraftReservation is missing locationId")),
                r.getReservationTypeId(),
                r.getFlightTypeId(),
                Objects.requireNonNull(r.getReservationStart(),
                        "AircraftReservation is missing reservationStart"),
                Objects.requireNonNull(r.getReservationEnd(),
                        "AircraftReservation is missing reservationEnd"),
                r.isAllDay(),
                r.getInfo());
    }

    static AircraftReservationDetail toDetail(ListRow row) {
        return new AircraftReservationDetail(
                row.id(),
                new java.util.UUID(0L, 0L),
                AircraftId.of(row.aircraftId()),
                PersonId.of(row.pilotPersonId()),
                PersonId.ofNullable(row.secondCrewPersonId()),
                LocationId.of(row.locationId()),
                row.reservationTypeId(),
                row.flightTypeId(),
                row.reservationStart(),
                row.reservationEnd(),
                row.allDay(),
                row.info());
    }

    static AircraftReservationListItem toListItem(ListItemRow row) {
        return new AircraftReservationListItem(
                row.id(),
                AircraftId.of(row.aircraftId()),
                row.reservationStart(),
                row.reservationEnd(),
                row.allDay(),
                PersonId.of(row.pilotPersonId()),
                PersonId.ofNullable(row.secondCrewPersonId()),
                LocationId.of(row.locationId()),
                row.reservationTypeId(),
                row.reservationTypeName(),
                row.flightTypeId(),
                row.info());
    }

    static AircraftReservationTypeListItem toTypeListItem(TypeListItem row) {
        return new AircraftReservationTypeListItem(
                row.id(), row.name(), row.active(), row.instructorRequired());
    }
}
