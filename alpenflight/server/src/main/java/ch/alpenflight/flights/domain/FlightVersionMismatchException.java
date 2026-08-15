package ch.alpenflight.flights.domain;

public class FlightVersionMismatchException extends RuntimeException {

    private final long expected;
    private final long actual;

    public FlightVersionMismatchException(long expected, long actual) {
        super("If-Match version " + expected + " does not match stored version " + actual);
        this.expected = expected;
        this.actual = actual;
    }

    public long expected() {
        return expected;
    }

    public long actual() {
        return actual;
    }
}
