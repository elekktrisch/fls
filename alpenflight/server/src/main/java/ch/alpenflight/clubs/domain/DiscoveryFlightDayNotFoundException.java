package ch.alpenflight.clubs.domain;

import java.util.UUID;

/**
 * Thrown when a discovery-flight-day endpoint is asked to mutate a row that is
 * absent, already withdrawn, or another club's. Hibernate's {@code @TenantId}
 * filter scrubs a foreign club's row from the result set, so the service cannot
 * tell "never existed" from "belongs to another tenant" — 404, never 403, is
 * the IDOR contract (S-159).
 */
public class DiscoveryFlightDayNotFoundException extends RuntimeException {

    public DiscoveryFlightDayNotFoundException(UUID id) {
        super("DiscoveryFlightDay not found: " + id);
    }
}
