package ch.alpenflight.accounting.domain;

import java.util.UUID;

public class DeliveryDeletionConflictException extends RuntimeException {

    public DeliveryDeletionConflictException(UUID deliveryId, UUID flightId, long deliveryCount) {
        super("Delivery " + deliveryId + " cannot be deleted: " + deliveryCount
                + " deliveries are linked to flight " + flightId
                + " — delete them together or none, so the flight state stays consistent");
    }
}
