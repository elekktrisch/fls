package ch.alpenflight.flights.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Smoke tests for {@link FlightValidator} — port of legacy
 * {@code FlightService.cs:1073-1136} (ValidateFlightBasics) + the start-
 * type-specific arms at {@code :987-1039}.
 *
 * <p>S-062a ships the pure-function port + per-rule smoke; depth (every
 * field combo, route-allow-list edge cases, multi-error aggregation) lives
 * in S-101.
 */
class FlightValidatorTest {

    private static final UUID AIRCRAFT = UUID.fromString("019e2e15-2c00-7af9-8000-0000000000a1");
    private static final UUID PROCESS_STATE_NEW =
            UUID.fromString("019e2e15-2c00-7a98-8000-000000003a98");
    private static final UUID PILOT = UUID.fromString("019e30c3-2c00-7001-8000-00000000aaaa");
    private static final UUID CREW_TYPE_PILOT =
            UUID.fromString("019e2e15-2c00-76b0-8000-0000000036b0");
    private static final UUID CREW_TYPE_WINCH_OPERATOR =
            UUID.fromString("019e2e15-2c00-76b4-8000-0000000036b4");
    private static final UUID START_TYPE_AEROTOW =
            UUID.fromString("019e2e15-2c00-7fa1-8000-000000000fa1");
    private static final UUID START_TYPE_EXTERNAL =
            UUID.fromString("019e2e15-2c00-7fa3-8000-000000000fa3");
    private static final UUID START_TYPE_WINCH =
            UUID.fromString("019e2e15-2c00-7fa0-8000-000000000fa0");
    private static final UUID START_LOC = UUID.fromString("019e2e15-2c00-7b00-8000-000000000b00");
    private static final UUID LDG_LOC = UUID.fromString("019e2e15-2c00-7b01-8000-000000000b01");
    private static final UUID FLIGHT_TYPE = UUID.fromString("019e2e15-2c00-7d00-8000-000000000d00");

    @Test
    void emptyFlight_reports_required_field_errors() {
        Flight f = Flight.createGlider(AIRCRAFT, PROCESS_STATE_NEW, emptyOps());
        List<FlightValidator.ValidationError> errors = FlightValidator.validate(f);
        assertThat(errors).extracting(FlightValidator.ValidationError::code)
                .contains(
                        "VALIDATION_ERROR_No_flight_date_set",
                        "VALIDATION_ERROR_No_pilot_set",
                        "VALIDATION_ERROR_No_start_time_information_set",
                        "VALIDATION_ERROR_No_landing_time_information_set",
                        "VALIDATION_ERROR_No_start_location_set",
                        "VALIDATION_ERROR_No_landing_location_set",
                        "VALIDATION_ERROR_No_start_type_set",
                        "VALIDATION_ERROR_No_flight_type_set");
    }

    @Test
    void noStartTimeInformation_flag_satisfies_start_time_rule() {
        Flight f = Flight.createGlider(AIRCRAFT, PROCESS_STATE_NEW,
                opsWithFlags(true, true));
        List<FlightValidator.ValidationError> errors = FlightValidator.validate(f);
        assertThat(errors).extracting(FlightValidator.ValidationError::code)
                .doesNotContain(
                        "VALIDATION_ERROR_No_start_time_information_set",
                        "VALIDATION_ERROR_No_landing_time_information_set");
    }

    @Test
    void landing_set_requires_nrOfLdgs() {
        Instant start = Instant.parse("2026-05-01T08:00:00Z");
        Instant ldg = Instant.parse("2026-05-01T09:00:00Z");
        Flight f = Flight.createGlider(AIRCRAFT, PROCESS_STATE_NEW,
                opsWithTimes(start, ldg, null));
        List<FlightValidator.ValidationError> errors = FlightValidator.validate(f);
        assertThat(errors).extracting(FlightValidator.ValidationError::code)
                .contains("VALIDATION_ERROR_Number_of_landings_not_set");
    }

    @Test
    void nrOfLdgs_zero_rejected_when_landing_set() {
        Instant start = Instant.parse("2026-05-01T08:00:00Z");
        Instant ldg = Instant.parse("2026-05-01T09:00:00Z");
        Flight f = Flight.createGlider(AIRCRAFT, PROCESS_STATE_NEW,
                opsWithTimes(start, ldg, (short) 0));
        List<FlightValidator.ValidationError> errors = FlightValidator.validate(f);
        assertThat(errors).extracting(FlightValidator.ValidationError::code)
                .contains("VALIDATION_ERROR_Number_of_landings_is_less_then_1");
    }

    @Test
    void pilot_crew_satisfies_pilot_rule() {
        Flight f = Flight.createGlider(AIRCRAFT, PROCESS_STATE_NEW, emptyOps());
        f.replaceCrew(List.of(new CrewMemberSpec(PILOT, CREW_TYPE_PILOT,
                null, null, null, null, null, null)));
        List<FlightValidator.ValidationError> errors = FlightValidator.validate(f);
        assertThat(errors).extracting(FlightValidator.ValidationError::code)
                .doesNotContain("VALIDATION_ERROR_No_pilot_set");
    }

    @Test
    void aerotow_glider_without_towLink_reports_towing_error() {
        Flight f = gliderWithStartType(START_TYPE_AEROTOW);
        List<FlightValidator.ValidationError> errors = FlightValidator.validate(f);
        assertThat(errors).extracting(FlightValidator.ValidationError::code)
                .contains("VALIDATION_ERROR_No_towing_flight_referenced_for_towed_glider_flight");
    }

    @Test
    void externalStart_with_towLink_reports_extraneous_tow_error() throws Exception {
        Flight glider = gliderWithStartType(START_TYPE_EXTERNAL);
        Flight tow = Flight.createTow(AIRCRAFT, PROCESS_STATE_NEW, emptyOps());
        UUID towId = UUID.fromString("019e30c3-2c00-7001-8000-0000000000dd");
        setField(tow, "id", towId);
        glider.linkTow(tow);
        List<FlightValidator.ValidationError> errors = FlightValidator.validate(glider);
        assertThat(errors).extracting(FlightValidator.ValidationError::code)
                .contains("VALIDATION_ERROR_Towing_flight_referenced_for_externally_started_glider_flight");
    }

    @Test
    void winchLaunch_without_winchOperator_reports_winch_error() {
        Flight f = gliderWithStartType(START_TYPE_WINCH);
        f.replaceCrew(List.of(new CrewMemberSpec(PILOT, CREW_TYPE_PILOT,
                null, null, null, null, null, null)));
        List<FlightValidator.ValidationError> errors = FlightValidator.validate(f);
        assertThat(errors).extracting(FlightValidator.ValidationError::code)
                .contains("VALIDATION_ERROR_No_winch_operator_set_for_winch_started_glider_flight");
    }

    @Test
    void winchLaunch_with_winchOperator_passes_winch_rule() {
        Flight f = gliderWithStartType(START_TYPE_WINCH);
        UUID winchOp = UUID.fromString("019e30c3-2c00-7001-8000-00000000ccc1");
        f.replaceCrew(List.of(
                new CrewMemberSpec(PILOT, CREW_TYPE_PILOT,
                        null, null, null, null, null, null),
                new CrewMemberSpec(winchOp, CREW_TYPE_WINCH_OPERATOR,
                        null, null, null, null, null, null)));
        List<FlightValidator.ValidationError> errors = FlightValidator.validate(f);
        assertThat(errors).extracting(FlightValidator.ValidationError::code)
                .doesNotContain("VALIDATION_ERROR_No_winch_operator_set_for_winch_started_glider_flight");
    }

    private static Flight gliderWithStartType(UUID startTypeId) {
        return Flight.createGlider(AIRCRAFT, PROCESS_STATE_NEW,
                opsForGliderWithStartType(startTypeId));
    }

    private static FlightOperationalData emptyOps() {
        return new FlightOperationalData(
                null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null,
                false, false,
                null, null, null, null, null, null,
                null, null, false);
    }

    private static FlightOperationalData opsWithFlags(boolean noStart, boolean noLdg) {
        return new FlightOperationalData(
                null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null,
                noStart, noLdg,
                null, null, null, null, null, null,
                null, null, false);
    }

    private static FlightOperationalData opsWithTimes(Instant start, Instant ldg, Short nrOfLdgs) {
        return new FlightOperationalData(
                LocalDate.of(2026, 5, 1),
                start, ldg, null, null,
                START_LOC, LDG_LOC,
                null, null, null, null,
                FLIGHT_TYPE, null,
                nrOfLdgs, null,
                false, false,
                null, null, null, null, null, null,
                null, null, false);
    }

    private static FlightOperationalData opsForGliderWithStartType(UUID startTypeId) {
        return new FlightOperationalData(
                LocalDate.of(2026, 5, 1),
                null, null, null, null,
                START_LOC, LDG_LOC,
                null, null, null, null,
                FLIGHT_TYPE, startTypeId,
                null, null,
                true, true,
                null, null, null, null, null, null,
                null, null, false);
    }

    private static void setField(Object target, String field, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
