package ch.alpenflight.reservations.application;

import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationDetail;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationTypeListItem;
import ch.alpenflight.reservations.domain.AircraftReservation;
import ch.alpenflight.reservations.domain.AircraftReservationRepository.ListRow;
import ch.alpenflight.reservations.domain.AircraftReservationRepository.TypeListItem;
import java.util.Objects;

/**
 * Maps the {@link AircraftReservation} aggregate / projection rows to the REST
 * DTOs (J-5 T-05). The application-layer mapper — NOT the migration-bundle
 * {@code AircraftReservationMapper} (which keys the legacy MSSQL export).
 *
 * <p>The detail mapper reads the aggregate's stored {@code reservationStart} /
 * {@code reservationEnd} (for all-day these are the normalised full-day span
 * {@code [date 00:00, date+1 00:00)} the factory wrote — see T-03), so the wire
 * shape is self-consistent with the {@code isAllDay} flag.
 */
final class AircraftReservationMapper {

    private AircraftReservationMapper() {}

    static AircraftReservationDetail toDetail(AircraftReservation r) {
        return new AircraftReservationDetail(
                Objects.requireNonNull(r.getId(), "Cannot map an unpersisted AircraftReservation"),
                Objects.requireNonNull(r.getOperatingClubId(),
                        "AircraftReservation is missing operatingClubId (@TenantId NOT NULL)"),
                Objects.requireNonNull(r.getAircraftId(), "AircraftReservation is missing aircraftId"),
                Objects.requireNonNull(r.getPilotPersonId(),
                        "AircraftReservation is missing pilotPersonId"),
                r.getSecondCrewPersonId(),
                Objects.requireNonNull(r.getLocationId(), "AircraftReservation is missing locationId"),
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
                // ListRow carries no operatingClubId (it's the tenant
                // discriminator, implicit on every tenant-scoped read). The
                // detail GET re-maps from the aggregate where it is populated;
                // this overload is reserved for list views (T-06/T-08) that
                // never surface the tenant id on the wire — emit the nil UUID
                // placeholder rather than a cross-module lookup.
                new java.util.UUID(0L, 0L),
                row.aircraftId(),
                row.pilotPersonId(),
                row.secondCrewPersonId(),
                row.locationId(),
                row.reservationTypeId(),
                row.flightTypeId(),
                row.reservationStart(),
                row.reservationEnd(),
                row.allDay(),
                row.info());
    }

    static AircraftReservationTypeListItem toTypeListItem(TypeListItem row) {
        return new AircraftReservationTypeListItem(row.id(), row.name(), row.active());
    }
}
