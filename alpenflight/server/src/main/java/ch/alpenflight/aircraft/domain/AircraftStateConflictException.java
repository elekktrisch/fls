package ch.alpenflight.aircraft.domain;

/**
 * Raised when a state-change attempts to violate the
 * "exactly one open state row per aircraft" invariant — either a stale
 * aggregate has been written concurrently (V3 partial unique
 * {@code ux_aas_current_state_per_aircraft} catches the race) or
 * {@code validFrom} is not strictly after the previous open state's
 * {@code validFrom}. Translated to HTTP 409.
 */
public class AircraftStateConflictException extends RuntimeException {

    public AircraftStateConflictException(String message) {
        super(message);
    }

    public AircraftStateConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
