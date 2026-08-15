package ch.alpenflight.flights.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FlightAirStateTest {

    private static final UUID AIRCRAFT = UUID.fromString("019e2e15-2c00-7af9-8000-0000000000a1");
    private static final UUID PROCESS_STATE_NEW =
            UUID.fromString("019e2e15-2c00-7a98-8000-000000003a98");
    private static final Instant T_START = Instant.parse("2026-05-01T08:00:00Z");
    private static final Instant T_LDG   = Instant.parse("2026-05-01T09:00:00Z");
    private static final Instant T_PLAN  = Instant.parse("2026-05-01T07:30:00Z");

    static Stream<Arguments> airStateTable() {
        return Stream.of(
                Arguments.of("landed_plain",
                        T_LDG, T_START, false, false, null, FlightAirState.LANDED),
                Arguments.of("landed_redundant_no_start_info",
                        T_LDG, null, false, true, null, FlightAirState.LANDED),
                Arguments.of("landed_redundant_no_ldg_info",
                        T_LDG, T_START, true, false, null, FlightAirState.LANDED),
                Arguments.of("landed_with_plan_open",
                        T_LDG, T_START, false, false, T_PLAN, FlightAirState.LANDED),
                Arguments.of("landed_without_start_asymmetric",
                        T_LDG, null, false, false, null, FlightAirState.LANDED),
                Arguments.of("might_be_landed_or_in_air",
                        null, T_START, true, false, null, FlightAirState.MIGHT_BE_LANDED_OR_IN_AIR),
                Arguments.of("started_plain",
                        null, T_START, false, false, null, FlightAirState.STARTED),
                Arguments.of("started_redundant_no_start_info",
                        null, T_START, false, true, null, FlightAirState.STARTED),
                Arguments.of("might_be_started",
                        null, null, false, true, null, FlightAirState.MIGHT_BE_STARTED),
                Arguments.of("flight_plan_open",
                        null, null, false, false, T_PLAN, FlightAirState.FLIGHT_PLAN_OPEN),
                Arguments.of("new_all_null",
                        null, null, false, false, null, FlightAirState.NEW),
                Arguments.of("no_ldg_info_without_start_falls_through",
                        null, null, true, false, null, FlightAirState.NEW),
                Arguments.of("no_ldg_info_without_start_plan_open",
                        null, null, true, false, T_PLAN, FlightAirState.FLIGHT_PLAN_OPEN));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("airStateTable")
    void airState_derivation(String name,
                             @Nullable Instant ldgDateTime,
                             @Nullable Instant startDateTime,
                             boolean noLdgTimeInformation,
                             boolean noStartTimeInformation,
                             @Nullable Instant flightPlanOpenedOn,
                             FlightAirState expected) throws Exception {
        Flight f = Flight.createGlider(AIRCRAFT, PROCESS_STATE_NEW,
                opsWith(startDateTime, ldgDateTime, noStartTimeInformation, noLdgTimeInformation));
        if (flightPlanOpenedOn != null) {
            setField(f, "flightPlanOpenedOn", flightPlanOpenedOn);
        }
        assertThat(f.airState()).isEqualTo(expected);
    }

    @Test
    void flightPlanClosed_never_emitted_for_any_input_combination() throws Exception {
        Instant[] ldgValues = { null, T_LDG };
        Instant[] startValues = { null, T_START };
        Instant[] planValues = { null, T_PLAN };
        for (Instant ldg : ldgValues) {
            for (Instant start : startValues) {
                for (boolean noLdg : new boolean[] { false, true }) {
                    for (boolean noStart : new boolean[] { false, true }) {
                        for (Instant plan : planValues) {
                            Flight f = Flight.createGlider(AIRCRAFT, PROCESS_STATE_NEW,
                                    opsWith(start, ldg, noStart, noLdg));
                            if (plan != null) {
                                setField(f, "flightPlanOpenedOn", plan);
                            }
                            assertThat(f.airState())
                                    .as("inputs ldg=%s start=%s noLdg=%s noStart=%s plan=%s",
                                            ldg, start, noLdg, noStart, plan)
                                    .isNotEqualTo(FlightAirState.FLIGHT_PLAN_CLOSED);
                        }
                    }
                }
            }
        }
    }

    @Test
    void airState_enum_order_and_legacy_codes_are_frozen() {
        assertThat(Arrays.stream(FlightAirState.values()).map(FlightAirState::legacyCode))
                .containsExactly((short) 0, (short) 5, (short) 8, (short) 10,
                        (short) 15, (short) 20, (short) 25);
    }

    private static FlightOperationalData opsWith(@Nullable Instant start,
                                                 @Nullable Instant ldg,
                                                 boolean noStartTimeInfo,
                                                 boolean noLdgTimeInfo) {
        return new FlightOperationalData(
                LocalDate.of(2026, 5, 1),
                start, ldg,
                null, null,
                null, null,
                null, null,
                null, null,
                null, null,
                null, null,
                noStartTimeInfo, noLdgTimeInfo,
                null, null,
                null, null,
                null,
                null,
                null, null,
                false);
    }

    private static void setField(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
