package ch.alpenflight.flights.domain;

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
