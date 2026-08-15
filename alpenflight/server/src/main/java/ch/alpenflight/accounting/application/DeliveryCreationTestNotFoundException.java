package ch.alpenflight.accounting.application;

import java.util.UUID;

public class DeliveryCreationTestNotFoundException extends RuntimeException {

    public DeliveryCreationTestNotFoundException(UUID id) {
        super("DeliveryCreationTest not found in the caller's tenant: " + id);
    }
}
