package ch.alpenflight.flights.domain;

/**
 * Thrown when a flight is otherwise eligible for a transition but the
 * S-061 <em>time-gate</em> has not yet elapsed:
 *
 * <ul>
 *   <li>{@link Gate#LOCK} — Valid → Locked attempted before
 *       {@code flight_date <= today - 2 days}.</li>
 *   <li>{@link Gate#BILL} — Locked → DeliveryPrepared attempted before
 *       {@code locked_at <= today - 3 days}.</li>
 * </ul>
 *
 * <p>Mapped to HTTP 409 Conflict by {@code FlightsExceptionHandler},
 * mirroring {@link IllegalFlightTransitionException}: the transition is
 * legal by the matrix but not yet permitted by the calendar gate, so the
 * client should surface "not yet — too recent" rather than "never".
 */
public class FlightGateNotReachedException extends RuntimeException {

    public enum Gate { LOCK, BILL }

    private final Gate gate;

    public FlightGateNotReachedException(Gate gate) {
        super(describe(gate));
        this.gate = gate;
    }

    public Gate gate() {
        return gate;
    }

    private static String describe(Gate gate) {
        return gate == Gate.LOCK
                ? "Flight cannot be locked yet: flight_date must be at least 2 days in the past"
                : "Flight cannot be billed yet: locked_at must be at least 3 days in the past";
    }
}
