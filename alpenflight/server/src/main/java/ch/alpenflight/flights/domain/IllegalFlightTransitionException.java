package ch.alpenflight.flights.domain;

import java.util.Collections;
import java.util.Set;

public class IllegalFlightTransitionException extends RuntimeException {

    private final FlightProcessState from;
    private final FlightProcessState to;
    private final TransitionTrigger trigger;
    private final Set<FlightProcessState> allowed;

    public IllegalFlightTransitionException(FlightProcessState from,
                                            FlightProcessState to,
                                            TransitionTrigger trigger) {
        this(from, to, trigger, Collections.emptySet());
    }

    public IllegalFlightTransitionException(FlightProcessState from,
                                            FlightProcessState to,
                                            TransitionTrigger trigger,
                                            Set<FlightProcessState> allowed) {
        super("Illegal flight transition under " + trigger + ": " + from + " -> " + to);
        this.from = from;
        this.to = to;
        this.trigger = trigger;
        this.allowed = Set.copyOf(allowed);
    }

    public FlightProcessState from() {
        return from;
    }

    public FlightProcessState to() {
        return to;
    }

    public TransitionTrigger trigger() {
        return trigger;
    }

    public Set<FlightProcessState> allowed() {
        return allowed;
    }
}
