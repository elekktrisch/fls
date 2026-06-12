package ch.alpenflight.reservations.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Cross-module count port owned by the {@code reservations} module (J-26 T-16).
 * Exposes the per-day aircraft-reservation count {@code planning} needs for its
 * legacy {@code NumberOfAircraftReservations} projection (J-6 behavior) WITHOUT
 * {@code planning} crossing into the {@code reservations} persistence: planning
 * reads the figure through this published {@link
 * org.springframework.modulith.NamedInterface named interface}.
 *
 * <p>Replaces the retired {@code planning-day-reservation-count} native-SQL
 * probe (its register entry's own "Remove when": a shared cross-module count
 * port so {@code planning} reads the figure through the reservations domain API
 * instead of native SQL). The implementation ({@code reservations.infra}) is
 * plain JPA over {@code AircraftReservation} — the {@code date(reservation_start)}
 * cast becomes a derived half-open range predicate
 * ({@code reservationStart >= dayStart AND reservationStart < dayStart + 1 day}),
 * and the tenant filter is the normal Hibernate {@code @TenantId} discriminator
 * (this runs in a real request tenant context), so there is NO native SQL.
 */
public interface ReservationCountPort {

    /**
     * The count of non-deleted aircraft reservations in the <em>current
     * tenant</em> whose start falls on the calendar day {@code date} (UTC) at
     * {@code locationId} — the legacy {@code NumberOfAircraftReservations}
     * (computed on read, never stored; J-6 oracle).
     *
     * <p>"On {@code date}" is the half-open instant window
     * {@code [date 00:00 UTC, date+1 00:00 UTC)} — the JPA equivalent of the
     * retired native {@code date(reservation_start) = :planningDate} cast under a
     * UTC session (the documented planning posture: pure-DATE {@code planning_date}
     * vs UTC instants). Tenant-scoped via the {@code @TenantId} discriminator on
     * {@code AircraftReservation} — the caller MUST run inside a resolved tenant
     * context (as the planning read path does).
     *
     * @param date the planning day's pure {@code DATE} (interpreted in UTC)
     * @param locationId the location the reservations must be booked at
     * @return the count of matching non-deleted reservations (≥ 0)
     */
    long countActiveOnDateAtLocation(LocalDate date, UUID locationId);
}
