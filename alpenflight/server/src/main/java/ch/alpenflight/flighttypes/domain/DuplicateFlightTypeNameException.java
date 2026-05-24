package ch.alpenflight.flighttypes.domain;

/**
 * Thrown when the chosen {@code flightTypeName} already exists for the
 * caller's tenant (active row only — soft-deleted siblings are allowed to
 * recreate the same name). Uniqueness is per-tenant: the V11 partial UNIQUE
 * on {@code (operating_club_id, flight_type_name) WHERE deleted_on IS NULL}
 * is the structural race catcher.
 */
public class DuplicateFlightTypeNameException extends RuntimeException {

    public DuplicateFlightTypeNameException(String flightTypeName) {
        super("FlightType name already in use: " + flightTypeName);
    }
}
