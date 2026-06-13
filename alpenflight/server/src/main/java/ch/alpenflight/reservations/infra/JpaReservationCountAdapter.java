package ch.alpenflight.reservations.infra;

import ch.alpenflight.reservations.api.ReservationCountPort;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed implementation of the cross-module {@link ReservationCountPort}
 * (J-26 T-16). Owned by the {@code reservations} module; {@code planning}
 * consumes it through the {@code reservation-count} named interface.
 *
 * <p>Replaces the retired {@code planning-day-reservation-count} native-SQL
 * probe. The legacy {@code date(reservation_start) = :planningDate} cast becomes
 * a derived half-open range over {@code AircraftReservation.reservationStart}:
 * the planning day's pure {@code DATE} maps to the UTC instant window
 * {@code [date 00:00 UTC, date+1 00:00 UTC)}. The count runs as plain JPQL
 * ({@link JpaAircraftReservationRepository#countActiveOnDayAtLocation}) so
 * Hibernate's {@code @TenantId} discriminator filters it to the current tenant
 * automatically (this runs in a real request tenant context — the planning read
 * path) and soft-deleted rows are excluded. NO native SQL.
 */
@Component
@Transactional(readOnly = true)
class JpaReservationCountAdapter implements ReservationCountPort {

    private final JpaAircraftReservationRepository reservations;

    JpaReservationCountAdapter(JpaAircraftReservationRepository reservations) {
        this.reservations = reservations;
    }

    @Override
    public long countActiveOnDateAtLocation(LocalDate date, UUID locationId) {
        // date(reservation_start) == planningDate (UTC) ⟺ the start falls in the
        // half-open UTC day window — a derived range predicate, no native cast.
        Instant dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return reservations.countActiveOnDayAtLocation(dayStart, dayEnd, locationId);
    }
}
