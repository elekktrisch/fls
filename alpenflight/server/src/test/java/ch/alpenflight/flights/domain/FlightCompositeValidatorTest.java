package ch.alpenflight.flights.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FlightCompositeValidator} — locks in the S-063 fork
 * outcome (b) AC-as-written: glider validity couples to tow validity via
 * a single sentinel error code rather than inheriting the tow's nested
 * error list. Degenerate cases (motor, tow-passed-directly) assert the
 * non-glider input paths fall through to per-flight validation only.
 */
class FlightCompositeValidatorTest {

    private static final UUID AIRCRAFT_GLIDER =
            UUID.fromString("019e2e15-2c00-7af9-8000-0000000000a1");
    private static final UUID AIRCRAFT_TOW =
            UUID.fromString("019e2e15-2c00-7af9-8000-0000000000a2");
    private static final UUID PROCESS_STATE_NEW =
            UUID.fromString("019e2e15-2c00-7a98-8000-000000003a98");
    private static final UUID PILOT =
            UUID.fromString("019e30c3-2c00-7001-8000-00000000aaaa");
    private static final UUID PILOT_TOW =
            UUID.fromString("019e30c3-2c00-7001-8000-00000000bbbb");
    private static final UUID CREW_TYPE_PILOT =
            UUID.fromString("019e2e15-2c00-76b0-8000-0000000036b0");
    private static final UUID START_TYPE_AEROTOW =
            UUID.fromString("019e2e15-2c00-7fa1-8000-000000000fa1");
    private static final UUID START_TYPE_SELF =
            UUID.fromString("019e2e15-2c00-7fa2-8000-000000000fa2");
    private static final UUID START_LOC =
            UUID.fromString("019e2e15-2c00-7b00-8000-000000000b00");
    private static final UUID LDG_LOC =
            UUID.fromString("019e2e15-2c00-7b01-8000-000000000b01");
    private static final UUID FLIGHT_TYPE =
            UUID.fromString("019e2e15-2c00-7d00-8000-000000000d00");
    private static final UUID GLIDER_ID =
            UUID.fromString("019e30c3-2c00-7001-8000-0000000000cc");
    private static final UUID TOW_ID =
            UUID.fromString("019e30c3-2c00-7001-8000-0000000000dd");

    @Test
    void glider_with_no_towFlight_returns_only_per_flight_errors() {
        Flight glider = validGlider(START_TYPE_SELF, /* towLinked= */ false);
        List<FlightValidator.ValidationError> errors =
                FlightCompositeValidator.validate(glider, id -> Optional.empty());
        assertThat(errors).extracting(FlightValidator.ValidationError::code)
                .doesNotContain(
                        "VALIDATION_ERROR_Tow_flight_invalid",
                        "VALIDATION_ERROR_Tow_flight_missing_or_deleted");
    }

    @Test
    void glider_with_valid_tow_appends_no_sentinel() throws Exception {
        Flight glider = validGlider(START_TYPE_AEROTOW, /* towLinked= */ true);
        Flight tow = validTow();
        List<FlightValidator.ValidationError> errors =
                FlightCompositeValidator.validate(glider, id -> Optional.of(tow));
        assertThat(errors).extracting(FlightValidator.ValidationError::code)
                .doesNotContain(
                        "VALIDATION_ERROR_Tow_flight_invalid",
                        "VALIDATION_ERROR_Tow_flight_missing_or_deleted");
    }

    @Test
    void glider_with_invalid_tow_appends_sentinel_not_nested_errors() throws Exception {
        Flight glider = validGlider(START_TYPE_AEROTOW, /* towLinked= */ true);
        Flight tow = invalidTow();
        List<FlightValidator.ValidationError> errors =
                FlightCompositeValidator.validate(glider, id -> Optional.of(tow));
        assertThat(errors).extracting(FlightValidator.ValidationError::code)
                .contains("VALIDATION_ERROR_Tow_flight_invalid")
                // Tow's own missing-pilot error must NOT appear in the glider
                // result — the AC says one sentinel per pair, not the nested
                // tow error list.
                .doesNotContain("VALIDATION_ERROR_No_pilot_set");
    }

    @Test
    void glider_with_missing_tow_target_appends_missing_or_deleted() throws Exception {
        Flight glider = validGlider(START_TYPE_AEROTOW, /* towLinked= */ true);
        List<FlightValidator.ValidationError> errors =
                FlightCompositeValidator.validate(glider, id -> Optional.empty());
        assertThat(errors).extracting(FlightValidator.ValidationError::code)
                .contains("VALIDATION_ERROR_Tow_flight_missing_or_deleted")
                .doesNotContain("VALIDATION_ERROR_Tow_flight_invalid");
    }

    @Test
    void glider_with_tombstoned_tow_target_appends_missing_or_deleted() throws Exception {
        Flight glider = validGlider(START_TYPE_AEROTOW, /* towLinked= */ true);
        Flight tow = validTow();
        tow.softDelete(java.time.Instant.parse("2026-05-01T08:00:00Z"));
        List<FlightValidator.ValidationError> errors =
                FlightCompositeValidator.validate(glider, id -> Optional.of(tow));
        assertThat(errors).extracting(FlightValidator.ValidationError::code)
                .contains("VALIDATION_ERROR_Tow_flight_missing_or_deleted")
                .doesNotContain("VALIDATION_ERROR_Tow_flight_invalid");
    }

    @Test
    void motor_flight_falls_through_to_per_flight_validation() {
        // Motor flights don't link to anything; passing one through the
        // composite validator must not attempt recursion.
        Flight motor = Flight.createMotor(AIRCRAFT_GLIDER, PROCESS_STATE_NEW,
                opsForFlight(START_TYPE_SELF));
        List<FlightValidator.ValidationError> errors =
                FlightCompositeValidator.validate(motor, id -> {
                    throw new AssertionError("lookup must not be called for MOTOR row");
                });
        assertThat(errors).extracting(FlightValidator.ValidationError::code)
                .doesNotContain(
                        "VALIDATION_ERROR_Tow_flight_invalid",
                        "VALIDATION_ERROR_Tow_flight_missing_or_deleted");
    }

    @Test
    void tow_passed_directly_is_no_op_for_pair_recursion() throws Exception {
        // Degenerate case: the daily-validation job iterates rows; a TOW row
        // passed in must not attempt any recursion (it has no towFlightId by
        // construction). Returns just its per-flight errors.
        Flight tow = validTow();
        List<FlightValidator.ValidationError> errors =
                FlightCompositeValidator.validate(tow, id -> {
                    throw new AssertionError("lookup must not be called for TOW row");
                });
        assertThat(errors).extracting(FlightValidator.ValidationError::code)
                .doesNotContain(
                        "VALIDATION_ERROR_Tow_flight_invalid",
                        "VALIDATION_ERROR_Tow_flight_missing_or_deleted");
    }

    private static Flight validGlider(UUID startTypeId, boolean towLinked) {
        Flight g = Flight.createGlider(AIRCRAFT_GLIDER, PROCESS_STATE_NEW,
                opsForFlight(startTypeId));
        g.replaceCrew(List.of(new CrewMemberSpec(PILOT, CREW_TYPE_PILOT,
                null, null, null, null, null, null)));
        try {
            setField(g, "id", GLIDER_ID);
            if (towLinked) {
                setField(g, "towFlightId", TOW_ID);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return g;
    }

    private static Flight validTow() throws Exception {
        Flight t = Flight.createTow(AIRCRAFT_TOW, PROCESS_STATE_NEW,
                opsForFlight(START_TYPE_SELF));
        t.replaceCrew(List.of(new CrewMemberSpec(PILOT_TOW, CREW_TYPE_PILOT,
                null, null, null, null, null, null)));
        setField(t, "id", TOW_ID);
        return t;
    }

    private static Flight invalidTow() throws Exception {
        // Tow with no pilot — surfaces VALIDATION_ERROR_No_pilot_set at the
        // FlightValidator layer.
        Flight t = Flight.createTow(AIRCRAFT_TOW, PROCESS_STATE_NEW,
                opsForFlight(START_TYPE_SELF));
        setField(t, "id", TOW_ID);
        return t;
    }

    private static FlightOperationalData opsForFlight(UUID startTypeId) {
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
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
