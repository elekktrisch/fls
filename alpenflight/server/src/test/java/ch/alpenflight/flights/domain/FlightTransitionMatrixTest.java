package ch.alpenflight.flights.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The flight-process-state transition matrix is the parity oracle for
 * S-059, hand-transcribed from the legacy switch at {@code
 * FlightService.cs:1375-1444} (operator-driven) and the system-driven sites
 * in {@code FlightService.cs:900-1075} (validator), {@code :1140-1185}
 * (lock job), and {@code DeliveryService.cs:130-200} (delivery prep) /
 * {@code :340-360} (booking).
 *
 * <p>Trigger scoping matters — {@code Valid → Locked} is rejected for
 * {@link TransitionTrigger#OPERATOR} but accepted for
 * {@link TransitionTrigger#LOCK_JOB}; {@code Locked → DeliveryPrepared} is
 * a {@link TransitionTrigger#DELIVERY_PREP}-only edge; etc.
 */
class FlightTransitionMatrixTest {

    /** The full legal set, by trigger. Mirrors legacy exactly. */
    private static Stream<Arguments> legalTransitions() {
        return Stream.of(
                // OPERATOR — FlightService.cs:1375-1444.
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

                // VALIDATOR — FlightService.cs:1041-1050. May stamp Valid OR Invalid
                // from NotProcessed; may stamp Invalid on a previously-Valid flight
                // whose data was edited (ModifiedOn >= ValidatedOn); may re-validate
                // an Invalid flight to Valid.
                Arguments.of(TransitionTrigger.VALIDATOR,
                        FlightProcessState.NOT_PROCESSED, FlightProcessState.VALID),
                Arguments.of(TransitionTrigger.VALIDATOR,
                        FlightProcessState.NOT_PROCESSED, FlightProcessState.INVALID),
                Arguments.of(TransitionTrigger.VALIDATOR,
                        FlightProcessState.INVALID, FlightProcessState.VALID),
                Arguments.of(TransitionTrigger.VALIDATOR,
                        FlightProcessState.VALID, FlightProcessState.INVALID),

                // LOCK_JOB — FlightService.cs:1140-1185.
                Arguments.of(TransitionTrigger.LOCK_JOB,
                        FlightProcessState.VALID, FlightProcessState.LOCKED),

                // DELIVERY_PREP — DeliveryService.cs:130-200.
                Arguments.of(TransitionTrigger.DELIVERY_PREP,
                        FlightProcessState.LOCKED, FlightProcessState.DELIVERY_PREPARED),
                Arguments.of(TransitionTrigger.DELIVERY_PREP,
                        FlightProcessState.LOCKED, FlightProcessState.DELIVERY_PREPARATION_ERROR),
                Arguments.of(TransitionTrigger.DELIVERY_PREP,
                        FlightProcessState.LOCKED, FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS),

                // BOOKING — DeliveryService.cs:340-360.
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

    /**
     * The negative oracle — every other (trigger, from, to) triple where
     * {@code from != to} must be illegal. Same-state edges are exercised
     * separately so the failure mode is distinguishable.
     */
    @Test
    void every_unenumerated_transition_is_illegal() {
        Set<String> legal = legalTransitions()
                .map(args -> args.get()[0] + "|" + args.get()[1] + "|" + args.get()[2])
                .collect(java.util.stream.Collectors.toSet());
        for (TransitionTrigger trigger : TransitionTrigger.values()) {
            for (FlightProcessState from : FlightProcessState.values()) {
                for (FlightProcessState to : FlightProcessState.values()) {
                    if (from == to) {
                        continue; // exercised by same_state_is_always_illegal.
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
            return; // covered by same-state rule
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
    void enum_codes_match_legacy_smallints() {
        // The codes are part of the public contract — the audit payload + e2e
        // parity all key off them. Don't drift.
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
        // Cheap aggregate-level sanity: the method propagates the matrix
        // decision. The matrix test above is the parity oracle.
        Flight f = newGliderInState(FlightProcessState.NOT_PROCESSED);
        assertThatThrownBy(() ->
                f.transition(FlightProcessState.LOCKED, TransitionTrigger.OPERATOR))
                .isInstanceOf(IllegalFlightTransitionException.class);
    }

    @Test
    void flight_transition_applies_legal_change() {
        Flight f = newGliderInState(FlightProcessState.VALID);
        f.transition(FlightProcessState.LOCKED, TransitionTrigger.LOCK_JOB);
        assertThat(f.getProcessState()).isEqualTo(FlightProcessState.LOCKED);
    }

    @Test
    void flight_transition_rejects_same_state() {
        Flight f = newGliderInState(FlightProcessState.VALID);
        assertThatThrownBy(() ->
                f.transition(FlightProcessState.VALID, TransitionTrigger.OPERATOR))
                .isInstanceOf(IllegalFlightTransitionException.class);
    }

    @Test
    void delivery_booked_is_sink_at_aggregate_level() {
        Flight f = newGliderInState(FlightProcessState.DELIVERY_BOOKED);
        for (FlightProcessState to : FlightProcessState.values()) {
            if (to == FlightProcessState.DELIVERY_BOOKED) {
                continue;
            }
            assertThatThrownBy(() -> f.transition(to, TransitionTrigger.OPERATOR))
                    .as("DELIVERY_BOOKED -> %s", to)
                    .isInstanceOf(IllegalFlightTransitionException.class);
        }
    }

    /**
     * Helper: build a glider in the given process state directly (bypasses
     * the create flow which always stamps NOT_PROCESSED). Uses reflection to
     * seed the package-private field — domain tests are allowed this since
     * they live in the same package.
     */
    private static Flight newGliderInState(FlightProcessState state) {
        // The factory accepts the initial process-state UUID; we pass the
        // target state's seeded UUID so the aggregate already carries the
        // matching enum before the test exercises a transition. Air-state
        // is computed (S-060), so no longer threaded through.
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
