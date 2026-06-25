package ch.alpenflight.accounting.domain;

import java.util.UUID;

/**
 * Raised when a delivery cannot be deleted because more than one active delivery is
 * linked to its flight ({@code DeliveryService.cs:1242} {@code >1-delivery-per-flight}
 * guard) — deleting one would leave the flight's process state ambiguous. The reset of
 * the flight to {@code Locked} only makes sense for the single-delivery case, so the
 * shared-flight case is a clean conflict: no partial mutation.
 *
 * <p>Legacy throws an unmapped {@code FLSServerException} (a raw 500); AlpenFlight
 * surfaces it as HTTP 409 ({@code DeliveriesExceptionHandler}) — correct conflict
 * semantics — so the screen can show the user why the delete was refused.
 */
public class DeliveryDeletionConflictException extends RuntimeException {

    public DeliveryDeletionConflictException(UUID deliveryId, UUID flightId, long deliveryCount) {
        super("Delivery " + deliveryId + " cannot be deleted: " + deliveryCount
                + " deliveries are linked to flight " + flightId
                + " — delete them together or none, so the flight state stays consistent");
    }
}
