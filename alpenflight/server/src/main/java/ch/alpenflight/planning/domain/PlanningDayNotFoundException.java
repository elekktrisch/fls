package ch.alpenflight.planning.domain;

import java.util.UUID;

/**
 * Raised when no active (non-deleted) planning day with the given id is visible
 * to the caller's tenant (J-6 T-04). Because reads are {@code @TenantId}-scoped,
 * a cross-tenant id is invisible and surfaces here — translated to HTTP 404 by
 * the planning web layer (the J-0/J-1/J-5 tenant-isolation pattern). The
 * exception stays free of Spring-web imports (ADR 0023); mirrors
 * {@code reservations}' {@code AircraftReservationNotFoundException}.
 */
public class PlanningDayNotFoundException extends RuntimeException {

    public PlanningDayNotFoundException(UUID id) {
        super("No active planning day with id " + id + " in the tenant");
    }
}
