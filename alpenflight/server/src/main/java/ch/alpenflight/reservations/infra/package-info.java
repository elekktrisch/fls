/**
 * Reservations persistence adapter — Spring Data JPA implementation of the
 * {@code reservations.domain} ports (J-5 T-04). {@link
 * ch.alpenflight.reservations.infra.JpaAircraftReservationRepository} extends
 * both {@link ch.alpenflight.reservations.domain.AircraftReservationRepository}
 * and Spring Data's {@code JpaRepository<AircraftReservation, UUID>}; the GiST
 * range-overlap conflict probe lives in the {@code AircraftReservationConflictProbe}
 * custom fragment (native SQL — the one tenant-scoped escape hatch, registered in
 * {@code native-sql-register.md}).
 *
 * <p>Per ADR 0023 nothing in {@code reservations.web} or
 * {@code reservations.application} may import from this package.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.reservations.infra;
