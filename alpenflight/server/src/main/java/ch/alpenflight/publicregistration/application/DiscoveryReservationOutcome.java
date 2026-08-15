package ch.alpenflight.publicregistration.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record DiscoveryReservationOutcome(Status status, @Nullable UUID reservationId) {

    public DiscoveryReservationOutcome {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if ((status == Status.BOOKED) != (reservationId != null)) {
            throw new IllegalArgumentException(
                    "a reservation id is carried by BOOKED and by nothing else, was " + status);
        }
    }

    public enum Status {

        BOOKED("discoveryFlight.reservation.booked"),

        SKIPPED_NO_DOUBLE_SEATER("discoveryFlight.reservation.skipped.noDoubleSeater"),

        SKIPPED_NO_HOMEBASE("discoveryFlight.reservation.skipped.noHomebase"),

        SKIPPED_NO_DOUBLE_SEATER_AND_NO_HOMEBASE(
                "discoveryFlight.reservation.skipped.noDoubleSeaterAndNoHomebase");

        private final String messageKey;

        Status(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    public static DiscoveryReservationOutcome booked(UUID reservationId) {
        return new DiscoveryReservationOutcome(Status.BOOKED, reservationId);
    }

    public static DiscoveryReservationOutcome skipped(boolean noDoubleSeater, boolean noHomebase) {
        if (!noDoubleSeater && !noHomebase) {
            throw new IllegalArgumentException("a skip needs a reason");
        }
        if (noDoubleSeater && noHomebase) {
            return new DiscoveryReservationOutcome(
                    Status.SKIPPED_NO_DOUBLE_SEATER_AND_NO_HOMEBASE, null);
        }
        return new DiscoveryReservationOutcome(
                noDoubleSeater ? Status.SKIPPED_NO_DOUBLE_SEATER : Status.SKIPPED_NO_HOMEBASE,
                null);
    }

    public boolean isBooked() {
        return status == Status.BOOKED;
    }

    public String messageKey() {
        return status.messageKey();
    }
}
