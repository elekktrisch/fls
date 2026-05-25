package ch.alpenflight.flights.domain;

/**
 * Thrown when a CRUD mutation hits a process-state gate.
 *
 * <ul>
 *   <li>{@link Reason#TERMINAL} — flight is {@code DELIVERY_BOOKED}; no
 *       update / delete is legal (mirrors legacy
 *       {@code FlightService.cs:1276-1280, 1308-1312}).</li>
 *   <li>{@link Reason#ADMIN_REQUIRED} — flight is at or past
 *       {@code LOCKED} and the caller is not a club administrator.
 *       Closes the legacy gap where line-pilots could edit
 *       Locked / DeliveryPrepared flights silently.</li>
 * </ul>
 */
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
