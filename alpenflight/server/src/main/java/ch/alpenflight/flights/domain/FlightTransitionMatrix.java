package ch.alpenflight.flights.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

final class FlightTransitionMatrix {

    private static final Map<TransitionTrigger,
            Map<FlightProcessState, Set<FlightProcessState>>> LEGAL = build();

    private FlightTransitionMatrix() {}

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

        Map<FlightProcessState, Set<FlightProcessState>> val = new EnumMap<>(FlightProcessState.class);
        val.put(FlightProcessState.NOT_PROCESSED,
                EnumSet.of(FlightProcessState.VALID, FlightProcessState.INVALID));
        val.put(FlightProcessState.INVALID,
                EnumSet.of(FlightProcessState.VALID));
        val.put(FlightProcessState.VALID,
                EnumSet.of(FlightProcessState.INVALID));
        m.put(TransitionTrigger.VALIDATOR, val);

        Map<FlightProcessState, Set<FlightProcessState>> lock = new EnumMap<>(FlightProcessState.class);
        lock.put(FlightProcessState.VALID, EnumSet.of(FlightProcessState.LOCKED));
        m.put(TransitionTrigger.LOCK_JOB, lock);

        Map<FlightProcessState, Set<FlightProcessState>> prep = new EnumMap<>(FlightProcessState.class);
        prep.put(FlightProcessState.LOCKED,
                EnumSet.of(FlightProcessState.DELIVERY_PREPARED,
                        FlightProcessState.DELIVERY_PREPARATION_ERROR,
                        FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS));
        m.put(TransitionTrigger.DELIVERY_PREP, prep);

        Map<FlightProcessState, Set<FlightProcessState>> book = new EnumMap<>(FlightProcessState.class);
        book.put(FlightProcessState.DELIVERY_PREPARED,
                EnumSet.of(FlightProcessState.DELIVERY_BOOKED));
        m.put(TransitionTrigger.BOOKING, book);

        return m;
    }
}
