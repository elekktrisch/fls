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
 *       {@code NumberOfAircraftReservations}: a count over the cross-module,
 *       tenant-scoped {@code t_aircraft_reservation} table keyed by
 *       {@code date(reservation_start) == planning_date} + same club + same
 *       location. Native SQL (the {@code date()} cast + cross-module table keep
 *       it out of the planning aggregate's JPQL), registered in
 *       {@code native-sql-register.md}.</li>
 * </ul>
 *
 * <p>Implemented by {@link PlanningDayPersistenceProbeImpl}, which injects the
 * {@code EntityManager} + the tenant resolver to bind the explicit
 * {@code operating_club_id} predicate the native count needs (Hibernate's
 * {@code @TenantId} discriminator does not apply to native SQL).
 */
interface PlanningDayPersistenceProbe {

    PlanningDay saveDedup(PlanningDay planningDay);

    long countReservationsForDay(LocalDate planningDate, UUID locationId);
}
