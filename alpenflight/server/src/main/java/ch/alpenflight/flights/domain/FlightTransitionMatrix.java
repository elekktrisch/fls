package ch.alpenflight.flights.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The legal {@code (trigger, from) -> to} transitions for the Flight
 * process state. Hand-transcribed from the legacy switch at
 * {@code FlightService.cs:1375-1444} (operator) plus the system-driven
 * sites at {@code :900-1075} (validator), {@code :1140-1185} (lock job),
 * {@code DeliveryService.cs:130-200} (delivery prep), and
 * {@code :340-360} (booking).
 *
 * <p>Same-state edges are NEVER legal — a transition to the current
 * state is rejected regardless of trigger. {@link FlightProcessState#DELIVERY_BOOKED}
 * is terminal: no outbound edge exists for any trigger.
 *
 * <p>Per ADR 0022 directive 2 the matrix is pure Java; no equivalent
 * CHECK constraint or trigger lives in the schema.
 */
final class FlightTransitionMatrix {

    private static final Map<TransitionTrigger,
            Map<FlightProcessState, Set<FlightProcessState>>> LEGAL = build();

    private FlightTransitionMatrix() {}

    /** True iff the given transition is allowed for the given trigger. */
    static boolean isLegal(TransitionTrigger trigger,
                           FlightProcessState from,
                           FlightProcessState to) {
        if (from == to) {
            return false;
        }
        Map<FlightProcessState, Set<FlightProcessState>> perTrigger = LEGAL.get(trigger);
        if (perTrigger == null) {
            return false;
        }
        Set<FlightProcessState> targets = perTrigger.get(from);
        return targets != null && targets.contains(to);
    }

    private static Map<TransitionTrigger,
            Map<FlightProcessState, Set<FlightProcessState>>> build() {
        Map<TransitionTrigger, Map<FlightProcessState, Set<FlightProcessState>>> m =
                new EnumMap<>(TransitionTrigger.class);

        // OPERATOR — FlightService.cs:1375-1444.
        Map<FlightProcessState, Set<FlightProcessState>> op = new EnumMap<>(FlightProcessState.class);
        op.put(FlightProcessState.VALID,
                EnumSet.of(FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS));
        op.put(FlightProcessState.LOCKED,
                EnumSet.of(FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS));
        op.put(FlightProcessState.DELIVERY_PREPARATION_ERROR,
                EnumSet.of(FlightProcessState.LOCKED,
                        FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS));
        op.put(FlightProcessState.DELIVERY_PREPARED,
                EnumSet.of(FlightProcessState.LOCKED,
                        FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS));
        op.put(FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS,
                EnumSet.of(FlightProcessState.VALID));
        m.put(TransitionTrigger.OPERATOR, op);

        // VALIDATOR — FlightService.cs:1041-1050.
        // NotProcessed → Valid or Invalid (first validation pass).
        // Invalid → Valid (re-validation succeeds).
        // Valid → Invalid (edit of a Valid flight re-validates to Invalid).
        Map<FlightProcessState, Set<FlightProcessState>> val = new EnumMap<>(FlightProcessState.class);
        val.put(FlightProcessState.NOT_PROCESSED,
                EnumSet.of(FlightProcessState.VALID, FlightProcessState.INVALID));
        val.put(FlightProcessState.INVALID,
                EnumSet.of(FlightProcessState.VALID));
        val.put(FlightProcessState.VALID,
                EnumSet.of(FlightProcessState.INVALID));
        m.put(TransitionTrigger.VALIDATOR, val);

        // LOCK_JOB — FlightService.cs:1140-1185.
        Map<FlightProcessState, Set<FlightProcessState>> lock = new EnumMap<>(FlightProcessState.class);
        lock.put(FlightProcessState.VALID, EnumSet.of(FlightProcessState.LOCKED));
        m.put(TransitionTrigger.LOCK_JOB, lock);

        // DELIVERY_PREP — DeliveryService.cs:130-200.
        Map<FlightProcessState, Set<FlightProcessState>> prep = new EnumMap<>(FlightProcessState.class);
        prep.put(FlightProcessState.LOCKED,
                EnumSet.of(FlightProcessState.DELIVERY_PREPARED,
                        FlightProcessState.DELIVERY_PREPARATION_ERROR,
                        FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS));
        m.put(TransitionTrigger.DELIVERY_PREP, prep);

        // BOOKING — DeliveryService.cs:340-360.
        Map<FlightProcessState, Set<FlightProcessState>> book = new EnumMap<>(FlightProcessState.class);
        book.put(FlightProcessState.DELIVERY_PREPARED,
                EnumSet.of(FlightProcessState.DELIVERY_BOOKED));
        m.put(TransitionTrigger.BOOKING, book);

        return m;
    }
}
