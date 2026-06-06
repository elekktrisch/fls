/**
 * Planning module — the {@code PlanningDay} aggregate root + its child
 * {@code PlanningDayAssignment} rows + the {@code PlanningDayAssignmentType}
 * per-club lookup (J-6). A club fixes who is on duty (flight instructor, tow
 * pilot, flight operator) at a location for a date, and the day surfaces that
 * date's aircraft reservations.
 *
 * <p>Tenant-scoped via Hibernate's {@code @TenantId} discriminator on
 * {@code operating_club_id} (ADR 0008) — a planning day belongs to exactly one
 * operating club. The {@code location_id} / {@code assigned_person_id} FKs
 * reference cross-tenant catalog aggregates by raw UUID.
 *
 * <p>The load-bearing shape decision (J-6 carve): legacy surfaces 3 fixed
 * person pickers (Instructor / TowingPilot / FlightOperator) but stores them as
 * <em>generic</em> typed assignment rows keyed by a per-club
 * {@code PlanningDayAssignmentType} whose name resolves the well-known role
 * (case-insensitive German — {@code segelflugleiter→FLIGHT_OPERATOR},
 * {@code schlepppilot→TOWING_PILOT}, {@code fluglehrer→INSTRUCTOR}, mirroring
 * legacy {@code MappingExtensions.cs:3302/3325/3348}). The aggregate's
 * {@link ch.alpenflight.planning.domain.PlanningDay#assignRole} upserts-or-deletes
 * the assignment row for a role (null person clears it).
 *
 * <p>Per ADR 0022 directive 2 the business rules live on the aggregate, NOT the
 * schema. V4 deliberately drops {@code ck_pln_planning_date_reasonable}; the
 * planning-date sanity range is enforced on the {@code PlanningDay} constructor.
 * The duplicate-{@code (club,date,location)} rule is the repository/unique-index
 * layer's job (T-03, {@code ux_pln_club_date_loc}).
 *
 * <p>Declared an {@link org.springframework.modulith.ApplicationModule#type()
 * OPEN} Spring Modulith module (matching {@code reservations} / {@code aircraft})
 * so a cross-cutting consumer (the showcase seed loader) may build aggregates
 * through their factory.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
@org.jspecify.annotations.NullMarked
package ch.alpenflight.planning;
