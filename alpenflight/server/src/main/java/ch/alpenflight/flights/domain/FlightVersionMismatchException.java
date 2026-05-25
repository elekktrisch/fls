package ch.alpenflight.flights.domain;

/**
 * Thrown when a {@code PUT} carries an {@code If-Match} header whose value
 * differs from the stored {@link Flight#getVersion()}. Mapped to HTTP 412
 * Precondition Failed by the controller advice — RFC 7232 §3.1.
 *
 * <p>Concurrent modifications that occur DURING the in-flight transaction
 * surface as {@code ObjectOptimisticLockingFailureException} (mapped to
 * 409); the 412 path is the pre-load comparison.
 */
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
