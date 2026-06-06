/**
 * Reservations module — the {@code AircraftReservation} aggregate root + the
 * {@code AircraftReservationType} lookup (J-5). A club books an aircraft for a
 * window (timed or all-day); the system refuses a second booking that overlaps
 * the same aircraft.
 *
 * <p>Tenant-scoped via Hibernate's {@code @TenantId} discriminator on
 * {@code operating_club_id} (ADR 0008) — unlike the cross-tenant Aircraft
 * catalog, a reservation belongs to exactly one operating club. The aircraft
 * FK crosses tenants freely (legacy-open parity, operator 2026-06-06): an
 * operating club MAY reserve an aircraft managed by a different club, with no
 * charter gate.
 *
 * <p>Per ADR 0022 directive 2 the conflict + duration rules live on the
 * aggregate, NOT the schema — V4 deliberately ships NO {@code EXCLUDE}
 * constraint and NO {@code CHECK}:
 *
 * <ul>
 *   <li>{@link ch.alpenflight.reservations.domain.AircraftReservation#validateDuration()}
 *       — timed reservations reject {@code end <= start}; all-day normalises to
 *       the full-day span {@code [date 00:00, date+1 00:00)} (not the legacy
 *       zero-length {@code start==end} artifact).</li>
 *   <li>{@link ch.alpenflight.reservations.domain.AircraftReservation#conflictsWith}
 *       — half-open overlap on the SAME aircraft
 *       ({@code existing.start < new.end && new.start < existing.end}), so an
 *       adjacent {@code end == next.start} booking does NOT conflict; an edit
 *       does not conflict with itself (self-excluded by id).</li>
 * </ul>
 *
 * <p>The pure overlap predicate + effective-span computation live here; the DB
 * GiST range-probe query is the infra repository's job (T-04).
 *
 * <p>Declared an {@link org.springframework.modulith.ApplicationModule#type()
 * OPEN} Spring Modulith module (matching {@code aircraft} / {@code clubs}) so a
 * cross-cutting consumer — the {@code tenancy.showcase} seed loader — may build
 * {@link ch.alpenflight.reservations.domain.AircraftReservation} aggregates
 * through their factory.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
@org.jspecify.annotations.NullMarked
package ch.alpenflight.reservations;
