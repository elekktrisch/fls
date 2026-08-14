package ch.alpenflight.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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
        assertThat(PlanningRole.fromTypeName("Segelflugleiter")).isEqualTo(PlanningRole.FLIGHT_OPERATOR);
        assertThat(PlanningRole.fromTypeName("SCHLEPPPILOT")).isEqualTo(PlanningRole.TOWING_PILOT);
        assertThat(PlanningRole.fromTypeName("  fluglehrer  ")).isEqualTo(PlanningRole.INSTRUCTOR);
        assertThat(PlanningRole.fromTypeName("kassier")).isNull();

        PlanningDay day = day();

        assertThat(day.assignRole(PlanningRole.FLIGHT_OPERATOR, TYPE_FLIGHT_OP, PERSON_1)).isTrue();
        assertThat(day.getAssignments()).hasSize(1);
        assertThat(day.assignedPersonForType(TYPE_FLIGHT_OP)).contains(PERSON_1);

        assertThat(day.assignRole(PlanningRole.FLIGHT_OPERATOR, TYPE_FLIGHT_OP, PERSON_1)).isFalse();
        assertThat(day.getAssignments()).hasSize(1);

        assertThat(day.assignRole(PlanningRole.FLIGHT_OPERATOR, TYPE_FLIGHT_OP, PERSON_2)).isTrue();
        assertThat(day.getAssignments()).hasSize(1);
        assertThat(day.assignedPersonForType(TYPE_FLIGHT_OP)).contains(PERSON_2);

        assertThat(day.assignRole(PlanningRole.TOWING_PILOT, TYPE_TOW, PERSON_1)).isTrue();
        assertThat(day.getAssignments()).hasSize(2);
    }

    @Test
    void clearingARole_removesTheAssignmentRow() {
        PlanningDay day = day();
        day.assignRole(PlanningRole.FLIGHT_OPERATOR, TYPE_FLIGHT_OP, PERSON_1);
        day.assignRole(PlanningRole.TOWING_PILOT, TYPE_TOW, PERSON_2);
        assertThat(day.getAssignments()).hasSize(2);

        assertThat(day.assignRole(PlanningRole.FLIGHT_OPERATOR, TYPE_FLIGHT_OP, null)).isTrue();
        assertThat(day.getAssignments()).hasSize(1);
        assertThat(day.assignedPersonForType(TYPE_FLIGHT_OP)).isEmpty();

        assertThat(day.clearRole(PlanningRole.FLIGHT_OPERATOR, TYPE_FLIGHT_OP)).isFalse();

        assertThat(day.clearRole(PlanningRole.TOWING_PILOT, TYPE_TOW)).isTrue();
        assertThat(day.getAssignments()).isEmpty();
    }

    @Test
    void planningDateInvariant_rejectsOutOfRangeAndNull() {
        assertThatThrownBy(() ->
                PlanningDay.create(CLUB_A, LocalDate.of(1800, 1, 1), LOCATION, null))
                .isInstanceOf(InvalidPlanningDateException.class);
        assertThatThrownBy(() ->
                PlanningDay.create(CLUB_A, LocalDate.of(2200, 1, 1), LOCATION, null))
                .isInstanceOf(InvalidPlanningDateException.class);

        PlanningDay day = day();
        assertThatThrownBy(() -> day.reschedule(LocalDate.of(1800, 1, 1)))
                .isInstanceOf(InvalidPlanningDateException.class);
        assertThat(day.getPlanningDate()).isEqualTo(LocalDate.of(2026, 6, 6));
    }

    @Test
    void expandRuleDates_emitsOneDatePerMatchingWeekday_ascending() {
        LocalDate start = LocalDate.of(2026, 6, 6);
        LocalDate end = LocalDate.of(2026, 6, 19);
        Set<DayOfWeek> weekend = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

        List<LocalDate> dates = PlanningDay.expandRuleDates(start, end, weekend);

        assertThat(dates).containsExactly(
                LocalDate.of(2026, 6, 6), LocalDate.of(2026, 6, 7),
                LocalDate.of(2026, 6, 13), LocalDate.of(2026, 6, 14));
        assertThat(dates).allMatch(d -> weekend.contains(d.getDayOfWeek()));
    }

    @Test
    void expandRuleDates_emptyFlags_orInvertedRange_returnEmpty() {
        LocalDate start = LocalDate.of(2026, 6, 6);
        LocalDate end = LocalDate.of(2026, 6, 19);

        assertThat(PlanningDay.expandRuleDates(start, end, Set.of())).isEmpty();
        assertThat(PlanningDay.expandRuleDates(end, start, EnumSet.of(DayOfWeek.SATURDAY))).isEmpty();
    }

    @Test
    void expandRuleDates_overSpanCap_throws() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate tooFar = start.plusDays(PlanningDay.MAX_RULE_SPAN_DAYS);
        assertThatThrownBy(() ->
                PlanningDay.expandRuleDates(start, tooFar, EnumSet.of(DayOfWeek.MONDAY)))
                .isInstanceOf(PlanningRuleRangeException.class);

        LocalDate atCap = start.plusDays(PlanningDay.MAX_RULE_SPAN_DAYS - 1);
        assertThat(PlanningDay.expandRuleDates(start, atCap, EnumSet.of(DayOfWeek.MONDAY)))
                .isNotEmpty();
    }
}
