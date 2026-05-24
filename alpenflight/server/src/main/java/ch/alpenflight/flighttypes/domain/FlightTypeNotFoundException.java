package ch.alpenflight.flighttypes.domain;

import ch.alpenflight.platform.id.FlightTypeId;

/**
 * Thrown when a FlightType endpoint is asked to read / mutate a non-existent
 * or soft-deleted row, including the cross-tenant case (Hibernate's
 * {@code @TenantId} filter scrubs it from the result set, so the service
 * cannot distinguish "doesn't exist" from "belongs to another tenant" —
 * 404, never 403, is the IDOR contract per S-159).
 */
public class FlightTypeNotFoundException extends RuntimeException {

    public FlightTypeNotFoundException(FlightTypeId id) {
        super("FlightType not found: " + id);
    }
}
