package ch.alpenflight.locations.domain;

import java.util.UUID;

/**
 * Domain event published by Spring Data on every {@link LocationRepository}
 * save via {@link Location}'s {@code @DomainEvents} method (the Flight /
 * Deployment precedent, J-7 RM-2). The flight-report read-model listens
 * synchronously (same transaction — ADR 0027 §2) and re-projects the rows
 * whose denormalized {@code start_location_name} / {@code ldg_location_name}
 * / tow-block location strings derive from this location.
 *
 * <p>Carries only the id: the listener resolves the affected flights through
 * the tenant-scoped flight repository. Location is itself tenant-scoped, so
 * the rename and every affected flight share one club by construction.
 */
public record LocationSaved(UUID locationId) {

    public LocationSaved {
        if (locationId == null) {
            throw new IllegalArgumentException("locationId must not be null");
        }
    }
}
