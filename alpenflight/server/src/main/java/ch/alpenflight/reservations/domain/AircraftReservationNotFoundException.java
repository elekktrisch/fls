package ch.alpenflight.reservations.domain;

import java.util.UUID;

/**
 * Thrown when a reservation endpoint is asked to read / mutate a non-existent,
 * soft-deleted, or cross-tenant ({@code @TenantId}-invisible) reservation.
 * Translated to HTTP 404 by the reservations web layer; the domain exception
 * stays free of Spring web imports per ADR 0023.
 */
public class AircraftReservationNotFoundException extends RuntimeException {

    public AircraftReservationNotFoundException(UUID id) {
        super("Aircraft reservation not found: " + id);
    }
}
