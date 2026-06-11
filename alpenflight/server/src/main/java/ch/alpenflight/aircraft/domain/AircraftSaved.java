package ch.alpenflight.aircraft.domain;

import java.util.UUID;

/**
 * Domain event published by Spring Data on every {@code AircraftRepository}
 * save via {@link Aircraft}'s {@code @DomainEvents} method (the Flight /
 * Deployment precedent, J-7 RM-2). The flight-report read-model listens
 * synchronously (same transaction — ADR 0027 §2) and re-projects the rows
 * whose denormalized {@code immatriculation} / {@code tow_immatriculation}
 * strings derive from this aircraft.
 *
 * <p>Carries only the id: the listener resolves the affected flights through
 * the tenant-scoped flight repository, so the refresh stays inside the
 * mutating principal's tenant (ADR 0008).
 */
public record AircraftSaved(UUID aircraftId) {

    public AircraftSaved {
        if (aircraftId == null) {
            throw new IllegalArgumentException("aircraftId must not be null");
        }
    }
}
