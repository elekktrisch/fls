package ch.alpenflight.flights.application;

/**
 * Thrown by {@link FlightListCursor#decode(String)} when the opaque cursor
 * string can't be parsed. Distinct from {@link IllegalArgumentException} so
 * {@code FlightsExceptionHandler} can map it to a dedicated problem-type
 * URI without sniffing message prefixes.
 */
public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(String message) {
        super(message);
    }

    public InvalidCursorException(String message, Throwable cause) {
        super(message, cause);
    }
}
