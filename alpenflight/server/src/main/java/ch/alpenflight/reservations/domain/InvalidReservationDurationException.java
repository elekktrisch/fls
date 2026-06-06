package ch.alpenflight.reservations.domain;

/**
 * Raised when a timed reservation's {@code end} is not strictly after its
 * {@code start} (per ADR 0022 directive 2 — the V4 schema deliberately omits
 * the {@code ck_arv_end_after_start} CHECK; the rule lives on
 * {@link AircraftReservation#validateDuration()}). Translated to HTTP 422 by
 * the reservations web layer; the domain exception stays free of Spring web
 * imports per ADR 0023.
 */
public class InvalidReservationDurationException extends RuntimeException {

    public InvalidReservationDurationException(String message) {
        super(message);
    }
}
