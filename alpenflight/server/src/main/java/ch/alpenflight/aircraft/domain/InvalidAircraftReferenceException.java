package ch.alpenflight.aircraft.domain;

/**
 * Raised when an Aircraft request carries a reference (aircraft_type_id,
 * aircraft_state_id, homebase_id, counter_unit_type_id, owner_club_id) that
 * does not exist in its catalog or has been soft-deleted. Translated to
 * HTTP 400 by {@code AircraftsExceptionHandler}.
 */
public class InvalidAircraftReferenceException extends RuntimeException {

    private final String field;

    public InvalidAircraftReferenceException(String field) {
        super("Unknown " + field);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
