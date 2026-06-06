package ch.alpenflight.reservations.domain;

/**
 * Raised when a reservation overlaps an existing, non-deleted reservation on
 * the SAME aircraft (half-open overlap per
 * {@link AircraftReservation#conflictsWith}). The guard is net-new corrected
 * behavior (legacy double-books freely) grounded in the V4 schema design
 * (per ADR 0022 directive 2 — no DB {@code EXCLUDE} constraint; the rule lives
 * on the aggregate). Translated to HTTP 409 (key
 * {@code aircraft.reservation.overlap}) by the reservations web layer; the
 * domain exception stays free of Spring web imports per ADR 0023.
 */
public class ReservationConflictException extends RuntimeException {

    public ReservationConflictException(String message) {
        super(message);
    }
}
