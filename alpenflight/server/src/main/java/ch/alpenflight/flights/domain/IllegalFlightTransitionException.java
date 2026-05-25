package ch.alpenflight.flights.domain;

import java.util.Collections;
import java.util.Set;

/**
 * Thrown when a requested {@link FlightProcessState} transition is not
 * legal for the given {@link TransitionTrigger} and current state. Mapped
 * to HTTP 409 Conflict by {@code FlightsExceptionHandler}; the response
 * body carries the {@code from}, {@code to}, and (where known) the set
 * of legal {@code to} values so the client can render a useful error.
 */
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
