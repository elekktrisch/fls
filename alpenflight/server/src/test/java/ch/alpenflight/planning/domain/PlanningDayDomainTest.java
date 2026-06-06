package ch.alpenflight.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit domain tests for {@link PlanningDay} (no Spring, no DB). Covers the
 * J-6 aggregate seam: role upsert/clear round-trips, the planning-date sanity
 * invariant, and that clearing a role removes the assignment row. Real
 * persistence + dedup ITs are T-03/T-04's job.
 */
class PlanningDayDomainTest {

    private static final UUID CLUB_A = UUID.fromString("019e30c3-2c00-7101-8000-000000000001");
    private static final UUID LOCATION = UUID.fromString("019e30c3-2c00-7102-8000-000000000001");
    private static final UUID TYPE_FLIGHT_OP = UUID.fromString("019e30c3-2c00-7103-8000-000000000001");
    private static final UUID TYPE_TOW = UUID.fromString("019e30c3-2c00-7103-8000-000000000002");
    private static final UUID PERSON_1 = UUID.fromString("019e30c3-2c00-7104-8000-000000000001");
    private static final UUID PERSON_2 = UUID.fromString("019e30c3-2c00-7104-8000-000000000002");

    private static PlanningDay day() {
        return PlanningDay.create(CLUB_A, LocalDate.of(2026, 6, 6), LOCATION, "weekend ops");
    }

    @Test
    void roleUpsert_roundTrips_addsThenRepointsTheSameRow() {
        // Role resolution is name-based, case-insensitive German (legacy parity).
        assertThat(PlanningRole.fromTypeName("Segelflugleiter")).isEqualTo(PlanningRole.FLIGHT_OPERATOR);
        assertThat(PlanningRole.fromTypeName("SCHLEPPPILOT")).isEqualTo(PlanningRole.TOWING_PILOT);
        assertThat(PlanningRole.fromTypeName("  fluglehrer  ")).isEqualTo(PlanningRole.INSTRUCTOR);
        assertThat(PlanningRole.fromTypeName("kassier")).isNull();

        PlanningDay day = day();

        // First assign -> a new row is added for the role's type.
        assertThat(day.assignRole(PlanningRole.FLIGHT_OPERATOR, TYPE_FLIGHT_OP, PERSON_1)).isTrue();
        assertThat(day.getAssignments()).hasSize(1);
        assertThat(day.assignedPersonForType(TYPE_FLIGHT_OP)).contains(PERSON_1);

        // Re-assigning the SAME person is a no-op (no change).
        assertThat(day.assignRole(PlanningRole.FLIGHT_OPERATOR, TYPE_FLIGHT_OP, PERSON_1)).isFalse();
        assertThat(day.getAssignments()).hasSize(1);

        // Re-assigning a DIFFERENT person re-points the existing row (upsert,
        // not a second row — mirrors legacy + the ux_pda_composite key).
        assertThat(day.assignRole(PlanningRole.FLIGHT_OPERATOR, TYPE_FLIGHT_OP, PERSON_2)).isTrue();
        assertThat(day.getAssignments()).hasSize(1);
        assertThat(day.assignedPersonForType(TYPE_FLIGHT_OP)).contains(PERSON_2);

        // A different role/type adds its own independent row.
        assertThat(day.assignRole(PlanningRole.TOWING_PILOT, TYPE_TOW, PERSON_1)).isTrue();
        assertThat(day.getAssignments()).hasSize(2);
    }

    @Test
    void clearingARole_removesTheAssignmentRow() {
        PlanningDay day = day();
        day.assignRole(PlanningRole.FLIGHT_OPERATOR, TYPE_FLIGHT_OP, PERSON_1);
        day.assignRole(PlanningRole.TOWING_PILOT, TYPE_TOW, PERSON_2);
        assertThat(day.getAssignments()).hasSize(2);

        // Clear via null person (== clearRole): the row is removed.
        assertThat(day.assignRole(PlanningRole.FLIGHT_OPERATOR, TYPE_FLIGHT_OP, null)).isTrue();
        assertThat(day.getAssignments()).hasSize(1);
        assertThat(day.assignedPersonForType(TYPE_FLIGHT_OP)).isEmpty();

        // Clearing an already-empty role is a no-op.
        assertThat(day.clearRole(PlanningRole.FLIGHT_OPERATOR, TYPE_FLIGHT_OP)).isFalse();

        // The other role is untouched.
        assertThat(day.clearRole(PlanningRole.TOWING_PILOT, TYPE_TOW)).isTrue();
        assertThat(day.getAssignments()).isEmpty();
    }

    @Test
    void planningDateInvariant_rejectsOutOfRangeAndNull() {
        // Garbage far-past / far-future dates are rejected at construction.
        assertThatThrownBy(() ->
                PlanningDay.create(CLUB_A, LocalDate.of(1800, 1, 1), LOCATION, null))
                .isInstanceOf(InvalidPlanningDateException.class);
        assertThatThrownBy(() ->
                PlanningDay.create(CLUB_A, LocalDate.of(2200, 1, 1), LOCATION, null))
                .isInstanceOf(InvalidPlanningDateException.class);

        // A reschedule to a bad date is rejected too.
        PlanningDay day = day();
        assertThatThrownBy(() -> day.reschedule(LocalDate.of(1800, 1, 1)))
                .isInstanceOf(InvalidPlanningDateException.class);
        assertThat(day.getPlanningDate()).isEqualTo(LocalDate.of(2026, 6, 6));
    }
}
