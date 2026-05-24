package ch.alpenflight.flights.domain;

import java.util.UUID;

/**
 * Port exposing the tenant-aware "does this aircraft exist under the
 * caller's tenant?" check. Implemented in {@code flights.infra} as a
 * Hibernate {@code @TenantId}-filtered projection lookup; the service
 * layer depends on this port per ADR 0023.
 *
 * <p>Per S-159, {@code aircraft_id} is same-tenant by construction —
 * Hibernate hides cross-tenant rows. A {@code false} result means
 * "aircraft does not exist OR belongs to another tenant" (the IDOR
 * contract does not distinguish).
 */
public interface AircraftReferenceCheck {

    boolean isAccessibleAircraft(UUID aircraftId);
}
