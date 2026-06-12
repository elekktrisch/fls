package ch.alpenflight.flighttypes.domain;

/**
 * Thrown when the chosen {@code flightCode} is already used by another
 * active FlightType of the caller's tenant (soft-deleted rows excluded —
 * they may recreate the same code). Uniqueness is per-tenant: the V3
 * partial UNIQUE {@code ux_flight_type_club_code} on
 * {@code (operating_club_id, flight_code) WHERE flight_code IS NOT NULL
 * AND deleted_on IS NULL} is the structural race catcher.
 */
public class DuplicateFlightTypeCodeException extends RuntimeException {

    public DuplicateFlightTypeCodeException(String flightCode) {
        super("FlightType code already in use: " + flightCode);
    }
}
