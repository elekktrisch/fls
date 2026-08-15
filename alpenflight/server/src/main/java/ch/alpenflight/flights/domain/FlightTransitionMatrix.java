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
        Map<TransitionTrigger, Map<FlightProcessState, Set<FlightProcessState>>> legalByTrigger =
                new EnumMap<>(TransitionTrigger.class);

        Map<FlightProcessState, Set<FlightProcessState>> operatorEdges =
                new EnumMap<>(FlightProcessState.class);
        operatorEdges.put(FlightProcessState.VALID,
                EnumSet.of(FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS));
        operatorEdges.put(FlightProcessState.LOCKED,
                EnumSet.of(FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS));
        operatorEdges.put(FlightProcessState.DELIVERY_PREPARATION_ERROR,
                EnumSet.of(FlightProcessState.LOCKED,
                        FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS));
        operatorEdges.put(FlightProcessState.DELIVERY_PREPARED,
                EnumSet.of(FlightProcessState.LOCKED,
                        FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS));
        operatorEdges.put(FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS,
                EnumSet.of(FlightProcessState.VALID));
        legalByTrigger.put(TransitionTrigger.OPERATOR, operatorEdges);

        Map<FlightProcessState, Set<FlightProcessState>> validatorEdges =
                new EnumMap<>(FlightProcessState.class);
        validatorEdges.put(FlightProcessState.NOT_PROCESSED,
                EnumSet.of(FlightProcessState.VALID, FlightProcessState.INVALID));
        validatorEdges.put(FlightProcessState.INVALID,
                EnumSet.of(FlightProcessState.VALID));
        validatorEdges.put(FlightProcessState.VALID,
                EnumSet.of(FlightProcessState.INVALID));
        legalByTrigger.put(TransitionTrigger.VALIDATOR, validatorEdges);

        Map<FlightProcessState, Set<FlightProcessState>> lockJobEdges =
                new EnumMap<>(FlightProcessState.class);
        lockJobEdges.put(FlightProcessState.VALID, EnumSet.of(FlightProcessState.LOCKED));
        legalByTrigger.put(TransitionTrigger.LOCK_JOB, lockJobEdges);

        Map<FlightProcessState, Set<FlightProcessState>> deliveryPrepEdges =
                new EnumMap<>(FlightProcessState.class);
        deliveryPrepEdges.put(FlightProcessState.LOCKED,
                EnumSet.of(FlightProcessState.DELIVERY_PREPARED,
                        FlightProcessState.DELIVERY_PREPARATION_ERROR,
                        FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS));
        legalByTrigger.put(TransitionTrigger.DELIVERY_PREP, deliveryPrepEdges);

        Map<FlightProcessState, Set<FlightProcessState>> bookingEdges =
                new EnumMap<>(FlightProcessState.class);
        bookingEdges.put(FlightProcessState.DELIVERY_PREPARED,
                EnumSet.of(FlightProcessState.DELIVERY_BOOKED));
        legalByTrigger.put(TransitionTrigger.BOOKING, bookingEdges);

        return legalByTrigger;
    }
}
