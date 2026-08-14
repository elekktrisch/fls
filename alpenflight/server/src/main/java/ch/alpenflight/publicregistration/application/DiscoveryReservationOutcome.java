package ch.alpenflight.publicregistration.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a discovery-flight registration did about the candidate's aircraft slot —
 * the value the organiser notification reports back to the club.
 *
 * <p>A skipped reservation is NOT a failed registration: legacy books the slot
 * best-effort and completes the registration either way
 * ({@code RegistrationService.cs:152-199}), because a club that has not yet
 * configured its double-seater or its homebase still wants the candidate.
 *
 * <p>The reason is a {@link Status} token, never a rendered sentence. Legacy
 * hard-codes one German string per case in the service; AlpenFlight ships four
 * languages, so the copy belongs in the locale-keyed email template and only the
 * language-neutral {@link Status#messageKey()} crosses the boundary.
 */
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

    /**
     * The four outcomes legacy distinguishes: booked, plus one per combination
     * of the two missing prerequisites. Each carries the template key the
     * organiser mail resolves its localised sentence under.
     */
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

        /** Stable, language-neutral handle for the organiser-mail copy (DE/FR/IT/EN). */
        public String messageKey() {
            return messageKey;
        }
    }

    public static DiscoveryReservationOutcome booked(UUID reservationId) {
        return new DiscoveryReservationOutcome(Status.BOOKED, reservationId);
    }

    /** The skip case the two missing prerequisites add up to. */
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

    /** Shorthand for the organiser-mail model. */
    public String messageKey() {
        return status.messageKey();
    }
}
