package ch.alpenflight.accounting.domain;

/**
 * Thrown when a {@link DeliveryCreationTest} create/update would violate a
 * structural business invariant the aggregate owns (ADR 0022 directive 2 — the
 * schema stays structural, the rules live here):
 *
 * <ul>
 *   <li>{@code testName} must be non-blank. (The V4 column is {@code NOT NULL}
 *       but accepts blank — the aggregate makes "non-blank" the real invariant,
 *       so the list view always has a label.)</li>
 *   <li>{@code flightId} must be present. (The harness payload is meaningless
 *       without the flight it tests — it is the subject the engine runs against.)</li>
 * </ul>
 */
public class InvalidDeliveryCreationTestException extends RuntimeException {

    public InvalidDeliveryCreationTestException(String message) {
        super(message);
    }
}
