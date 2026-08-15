package ch.alpenflight.flights.domain;

public class FlightStateGateException extends RuntimeException {

    public enum Reason { TERMINAL, ADMIN_REQUIRED }

    private final FlightProcessState state;
    private final Reason reason;

    public FlightStateGateException(FlightProcessState state, Reason reason) {
        super("Flight is in " + state.name() + "; " + describe(reason));
        this.state = state;
        this.reason = reason;
    }

    public FlightProcessState state() {
        return state;
    }

    public Reason reason() {
        return reason;
    }

    private static String describe(Reason r) {
        return r == Reason.TERMINAL
                ? "mutations are no longer permitted"
                : "CLUB_ADMINISTRATOR role is required to mutate";
    }
}
