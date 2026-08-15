package ch.alpenflight.accounting.application;

import java.util.UUID;

public class DeliveryNotFoundException extends RuntimeException {

    public DeliveryNotFoundException(UUID id) {
        super("Delivery not found in the caller's tenant: " + id);
    }
}
