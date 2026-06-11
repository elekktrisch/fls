package ch.alpenflight.flighttypes.domain;

import java.util.UUID;

/**
 * Domain event published by Spring Data on every {@link FlightTypeRepository}
 * save via {@link FlightType}'s {@code @DomainEvents} method (the Flight /
 * Deployment precedent, J-7 RM-2). The flight-report read-model listens
 * synchronously (same transaction — ADR 0027 §2) and re-projects the rows
 * whose denormalized {@code flight_type_name} / {@code flight_code} (and the
 * tow-block copies) derive from this flight type.
 *
 * <p>Carries only the id: the listener resolves the affected flights through
 * the tenant-scoped flight repository. FlightType is itself tenant-scoped, so
 * the rename and every affected flight share one club by construction.
 */
public record FlightTypeSaved(UUID flightTypeId) {

    public FlightTypeSaved {
        if (flightTypeId == null) {
            throw new IllegalArgumentException("flightTypeId must not be null");
        }
    }
}
