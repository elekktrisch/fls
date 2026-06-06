package ch.alpenflight.planning.domain;

/**
 * Raised when a planning day would duplicate the {@code (operating_club_id,
 * planning_date, location_id)} identity of an existing, non-deleted day —
 * the {@code ux_pln_club_date_loc} UNIQUE rule (V4). Legacy had no such
 * constraint and silently re-created duplicates (J-6 behavior oracle, dedup
 * bug → corrected); V4 forces the dedup and the repository surfaces the breach
 * as this catchable domain exception rather than a raw constraint-violation
 * 500.
 *
 * <p>Translated to HTTP 409 (key {@code planning.day.duplicate}) by the
 * planning web layer (T-04); the domain exception stays free of Spring web
 * imports per ADR 0023. Mirrors {@code reservations}'
 * {@code ReservationConflictException} (the J-5 GiST 409).
 */
public class PlanningDayConflictException extends RuntimeException {

    public PlanningDayConflictException(String message) {
        super(message);
    }

    public PlanningDayConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
