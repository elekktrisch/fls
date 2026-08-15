package ch.alpenflight.accounting.domain;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class DeliveryBookedTerminalException extends RuntimeException {

    public DeliveryBookedTerminalException(@Nullable UUID deliveryId) {
        super("Delivery " + deliveryId + " is booked and can no longer be modified"
                + " — a booked delivery is an immutable billing record");
    }
}
