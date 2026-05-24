package ch.alpenflight.flights.domain;

import ch.alpenflight.platform.id.FlightId;

/**
 * Thrown when a Flight cannot be resolved by id under the caller's tenant
 * scope. The HTTP advice maps this to 404 — cross-tenant access is
 * indistinguishable from a missing row (IDOR contract, mirror of S-050 /
 * S-051).
 */
public class FlightNotFoundException extends RuntimeException {

    public FlightNotFoundException(FlightId id) {
        super("Flight " + id.toExternal() + " not found");
    }

    public FlightNotFoundException(String message) {
        super(message);
    }
}
