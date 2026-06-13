/**
 * Planning persistence adapter — Spring Data JPA implementation of the
 * {@code planning.domain} ports (J-6 T-03).
 * {@link ch.alpenflight.planning.infra.JpaPlanningDayRepository} extends both
 * {@link ch.alpenflight.planning.domain.PlanningDayRepository} and Spring Data's
 * {@code JpaRepository<PlanningDay, UUID>}; the dedup-aware save lives in the
 * {@code PlanningDayPersistenceProbe} custom fragment, which also surfaces the
 * per-day reservation count by delegating to the {@code reservations} module's
 * {@link ch.alpenflight.reservations.api.ReservationCountPort} named interface
 * (J-26 T-16 — the count is no longer native SQL; the
 * {@code planning-day-reservation-count} register hatch is retired).
 * {@link ch.alpenflight.planning.infra.JpaPlanningDayAssignmentTypeRepository}
 * gives the per-club assignment-type lookup its Spring Data binding.
 *
 * <p>Per ADR 0023 nothing in {@code planning.web} or
 * {@code planning.application} may import from this package.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.planning.infra;
