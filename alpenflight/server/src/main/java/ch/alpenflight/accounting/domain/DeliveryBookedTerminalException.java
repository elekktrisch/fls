package ch.alpenflight.accounting.domain;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Raised when a write is attempted on an already-booked delivery (state
 * {@link DeliveryProcessState#BOOKED}, legacy {@code IsFurtherProcessed=true}).
 * Booked is the terminal state: the delivery is the immutable billing record the
 * external finance system already consumed, so deleting it or re-booking it would
 * corrupt a closed accounting record.
 *
 * <p>Legacy leaves the booking + delete paths un-guarded (only the manual
 * flight-state path rejects, {@code FlightService.cs:1427}, with a 400) — a
 * reachable money/safety hole where a booked record can be silently un-booked or
 * re-stamped. AlpenFlight closes it: any mutation of a booked delivery surfaces as
 * HTTP 409 ({@code DeliveriesExceptionHandler}), no partial mutation.
 */
public class DeliveryBookedTerminalException extends RuntimeException {

    public DeliveryBookedTerminalException(@Nullable UUID deliveryId) {
        super("Delivery " + deliveryId + " is booked and can no longer be modified"
                + " — a booked delivery is an immutable billing record");
    }
}
