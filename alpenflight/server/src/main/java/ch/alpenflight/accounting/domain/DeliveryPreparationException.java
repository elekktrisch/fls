package ch.alpenflight.accounting.domain;

/**
 * Thrown when the rules engine's output cannot become a billable {@link Delivery}
 * for an eligible flight — no billing recipient resolved, no line items, or an
 * item whose {@code articleNumber} resolves to no article. In a delivery-create
 * batch this is a per-flight {@code DeliveryPreparationError}: the batch swallows
 * it (the flight is stamped error, no delivery is written) so one bad flight never
 * aborts the run ({@code DeliveryService.cs}).
 */
public class DeliveryPreparationException extends RuntimeException {

    public DeliveryPreparationException(String message) {
        super(message);
    }
}
