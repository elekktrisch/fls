package ch.alpenflight.planning.infra;

import ch.alpenflight.planning.domain.PlanningDay;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Spring Data custom-fragment seam for the two planning-day persistence
 * operations that can't be a derived / {@code @Query} method:
 *
 * <ul>
 *   <li>{@link #saveDedup} — a save that flushes immediately and translates a
 *       {@code ux_pln_club_date_loc} unique-index breach into the catchable
 *       {@code PlanningDayConflictException} (so T-04 maps it to 409 rather
 *       than a raw constraint-violation 500). Mirrors J-5's reservation
 *       conflict surfacing.</li>
 *   <li>{@link #countReservationsForDay} — the legacy
 *       {@code NumberOfAircraftReservations}: a count of the day's aircraft
 *       reservations at the same location. Since J-26 T-16 this reads through
 *       the {@code reservations} module's
 *       {@link ch.alpenflight.reservations.api.ReservationCountPort} named
 *       interface (plain JPA, {@code @TenantId}-filtered) instead of the retired
 *       {@code planning-day-reservation-count} native-SQL probe, so
 *       {@code planning} no longer reaches into {@code t_aircraft_reservation}.</li>
 * </ul>
 *
 * <p>Implemented by {@link PlanningDayPersistenceProbeImpl}, which injects the
 * {@code EntityManager} ({@link #saveDedup} flush) + the
 * {@link ch.alpenflight.reservations.api.ReservationCountPort} (the count).</p>
 */
interface PlanningDayPersistenceProbe {

    PlanningDay saveDedup(PlanningDay planningDay);

    long countReservationsForDay(LocalDate planningDate, UUID locationId);
}
