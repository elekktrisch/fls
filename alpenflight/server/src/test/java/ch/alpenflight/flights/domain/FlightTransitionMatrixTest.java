package ch.alpenflight.flights.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FlightTransitionMatrixTest {

    private static Stream<Arguments> legalTransitions() {
        return Stream.of(
                Arguments.of(TransitionTrigger.OPERATOR,
                        FlightProcessState.VALID, FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS),
                Arguments.of(TransitionTrigger.OPERATOR,
                        FlightProcessState.LOCKED, FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS),
                Arguments.of(TransitionTrigger.OPERATOR,
                        FlightProcessState.DELIVERY_PREPARATION_ERROR, FlightProcessState.LOCKED),
                Arguments.of(TransitionTrigger.OPERATOR,
                        FlightProcessState.DELIVERY_PREPARATION_ERROR, FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS),
                Arguments.of(TransitionTrigger.OPERATOR,
                        FlightProcessState.DELIVERY_PREPARED, FlightProcessState.LOCKED),
                Arguments.of(TransitionTrigger.OPERATOR,
                        FlightProcessState.DELIVERY_PREPARED, FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS),
                Arguments.of(TransitionTrigger.OPERATOR,
                        FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS, FlightProcessState.VALID),

                Arguments.of(TransitionTrigger.VALIDATOR,
                        FlightProcessState.NOT_PROCESSED, FlightProcessState.VALID),
                Arguments.of(TransitionTrigger.VALIDATOR,
                        FlightProcessState.NOT_PROCESSED, FlightProcessState.INVALID),
                Arguments.of(TransitionTrigger.VALIDATOR,
                        FlightProcessState.INVALID, FlightProcessState.VALID),
                Arguments.of(TransitionTrigger.VALIDATOR,
                        FlightProcessState.VALID, FlightProcessState.INVALID),

                Arguments.of(TransitionTrigger.LOCK_JOB,
                        FlightProcessState.VALID, FlightProcessState.LOCKED),

                Arguments.of(TransitionTrigger.DELIVERY_PREP,
                        FlightProcessState.LOCKED, FlightProcessState.DELIVERY_PREPARED),
                Arguments.of(TransitionTrigger.DELIVERY_PREP,
                        FlightProcessState.LOCKED, FlightProcessState.DELIVERY_PREPARATION_ERROR),
                Arguments.of(TransitionTrigger.DELIVERY_PREP,
                        FlightProcessState.LOCKED, FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS),

                Arguments.of(TransitionTrigger.BOOKING,
                        FlightProcessState.DELIVERY_PREPARED, FlightProcessState.DELIVERY_BOOKED));
    }

    @ParameterizedTest(name = "{0}: {1} -> {2} is legal")
    @MethodSource("legalTransitions")
    void legal_transition_is_allowed(TransitionTrigger trigger,
                                     FlightProcessState from,
                                     FlightProcessState to) {
        assertThat(FlightTransitionMatrix.isLegal(trigger, from, to))
                .as("%s: %s -> %s", trigger, from, to)
                .isTrue();
    }

    @Test
    void every_unenumerated_transition_is_illegal() {
        Set<String> legal = legalTransitions()
                .map(args -> args.get()[0] + "|" + args.get()[1] + "|" + args.get()[2])
                .collect(java.util.stream.Collectors.toSet());
        for (TransitionTrigger trigger : TransitionTrigger.values()) {
            for (FlightProcessState from : FlightProcessState.values()) {
                for (FlightProcessState to : FlightProcessState.values()) {
                    if (from == to) {
                        continue;
                    }
                    String key = trigger + "|" + from + "|" + to;
                    boolean expected = legal.contains(key);
                    assertThat(FlightTransitionMatrix.isLegal(trigger, from, to))
                            .as("%s: %s -> %s", trigger, from, to)
                            .isEqualTo(expected);
                }
            }
        }
    }

    @ParameterizedTest(name = "same-state {1} under {0} is rejected")
    @MethodSource("everyStateAndTrigger")
    void same_state_is_always_illegal(TransitionTrigger trigger,
                                      FlightProcessState state) {
        assertThat(FlightTransitionMatrix.isLegal(trigger, state, state)).isFalse();
    }

    private static Stream<Arguments> everyStateAndTrigger() {
        return Stream.of(TransitionTrigger.values())
                .flatMap(t -> Stream.of(FlightProcessState.values()).map(s -> Arguments.of(t, s)));
    }

    @ParameterizedTest(name = "DELIVERY_BOOKED -> {0} is rejected for {1}")
    @MethodSource("everyOtherStateAndTrigger")
    void delivery_booked_is_terminal(FlightProcessState to, TransitionTrigger trigger) {
        if (to == FlightProcessState.DELIVERY_BOOKED) {
            return;
        }
        assertThat(FlightTransitionMatrix.isLegal(trigger, FlightProcessState.DELIVERY_BOOKED, to))
                .as("DELIVERY_BOOKED -> %s under %s", to, trigger)
                .isFalse();
    }

    private static Stream<Arguments> everyOtherStateAndTrigger() {
        return Stream.of(FlightProcessState.values())
                .flatMap(s -> Stream.of(TransitionTrigger.values()).map(t -> Arguments.of(s, t)));
    }

    @Test
    void enum_codes_match_the_legacy_smallints_that_audit_payloads_and_e2e_parity_key_off() {
        assertThat(FlightProcessState.NOT_PROCESSED.legacyCode()).isEqualTo((short) 0);
        assertThat(FlightProcessState.INVALID.legacyCode()).isEqualTo((short) 28);
        assertThat(FlightProcessState.VALID.legacyCode()).isEqualTo((short) 30);
        assertThat(FlightProcessState.LOCKED.legacyCode()).isEqualTo((short) 40);
        assertThat(FlightProcessState.DELIVERY_PREPARATION_ERROR.legacyCode()).isEqualTo((short) 45);
        assertThat(FlightProcessState.DELIVERY_PREPARED.legacyCode()).isEqualTo((short) 50);
        assertThat(FlightProcessState.DELIVERY_BOOKED.legacyCode()).isEqualTo((short) 60);
        assertThat(FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS.legacyCode()).isEqualTo((short) 99);
    }

    @Test
    void flight_transition_throws_on_illegal() {
        Flight f = gliderSeededDirectlyInState(FlightProcessState.NOT_PROCESSED);
        assertThatThrownBy(() ->
                f.transition(FlightProcessState.LOCKED, TransitionTrigger.OPERATOR))
                .isInstanceOf(IllegalFlightTransitionException.class);
    }

    @Test
    void flight_transition_to_locked_stamps_lockedAt_from_the_supplied_instant() {
        Flight f = gliderSeededDirectlyInState(FlightProcessState.VALID);
        java.time.Instant lockJobInstant = java.time.Instant.parse("2026-01-01T12:00:00Z");
        f.transition(FlightProcessState.LOCKED, TransitionTrigger.LOCK_JOB, lockJobInstant);
        assertThat(f.getProcessState()).isEqualTo(FlightProcessState.LOCKED);
        assertThat(f.getLockedAt()).isEqualTo(lockJobInstant);
    }

    @Test
    void flight_transition_rejects_same_state() {
        Flight f = gliderSeededDirectlyInState(FlightProcessState.VALID);
        assertThatThrownBy(() ->
                f.transition(FlightProcessState.VALID, TransitionTrigger.OPERATOR))
                .isInstanceOf(IllegalFlightTransitionException.class);
    }

    @Test
    void bookDelivery_flipsPreparedToBooked_andRejectsFromNonPrepared() {
        Flight prepared = gliderSeededDirectlyInState(FlightProcessState.DELIVERY_PREPARED);
        prepared.bookDelivery();
        assertThat(prepared.getProcessState()).isEqualTo(FlightProcessState.DELIVERY_BOOKED);

        Flight booked = gliderSeededDirectlyInState(FlightProcessState.DELIVERY_BOOKED);
        assertThatThrownBy(booked::bookDelivery)
                .as("an already-booked flight rejects re-booking")
                .isInstanceOf(IllegalFlightTransitionException.class);
    }

    @Test
    void delivery_booked_is_sink_at_aggregate_level() {
        Flight f = gliderSeededDirectlyInState(FlightProcessState.DELIVERY_BOOKED);
        for (FlightProcessState to : FlightProcessState.values()) {
            if (to == FlightProcessState.DELIVERY_BOOKED) {
                continue;
            }
            assertThatThrownBy(() -> f.transition(to, TransitionTrigger.OPERATOR))
                    .as("DELIVERY_BOOKED -> %s", to)
                    .isInstanceOf(IllegalFlightTransitionException.class);
        }
    }

    private static Flight gliderSeededDirectlyInState(FlightProcessState state) {
        return Flight.createGlider(
                java.util.UUID.fromString("019e2e15-2c00-7af9-8000-0000000000a1"),
                state.id(),
                opsForTest());
    }

    private static FlightOperationalData opsForTest() {
        return new FlightOperationalData(
                java.time.LocalDate.of(2026, 5, 1),
                null, null, null, null,
                null, null,
                null, null,
                null, null,
                null, null,
                null, null,
                false, false,
                null, null,
                null, null,
                null,
                null,
                null, null,
                false);
    }
}
