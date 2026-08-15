package ch.alpenflight.aircraft.domain;

public class AircraftStateConflictException extends RuntimeException {

    public AircraftStateConflictException(String message) {
        super(message);
    }

    public AircraftStateConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
