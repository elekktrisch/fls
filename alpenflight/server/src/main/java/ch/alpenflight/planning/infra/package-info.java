/**
 * Planning persistence adapter — Spring Data JPA implementation of the
 * {@code planning.domain} ports (J-6 T-03).
 * {@link ch.alpenflight.planning.infra.JpaPlanningDayRepository} extends both
 * {@link ch.alpenflight.planning.domain.PlanningDayRepository} and Spring Data's
 * {@code JpaRepository<PlanningDay, UUID>}; the dedup-aware save + the native
 * per-day reservation count live in the {@code PlanningDayPersistenceProbe}
 * custom fragment (the count is the one tenant-scoped native escape hatch,
 * registered in {@code native-sql-register.md}).
 * {@link ch.alpenflight.planning.infra.JpaPlanningDayAssignmentTypeRepository}
 * gives the per-club assignment-type lookup its Spring Data binding.
 *
 * <p>Per ADR 0023 nothing in {@code planning.web} or
 * {@code planning.application} may import from this package.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.planning.infra;
