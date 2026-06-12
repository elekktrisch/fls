package ch.alpenflight.flights.domain;

import java.util.UUID;

/**
 * Domain event published by Spring Data on every {@link FlightRepository#save}
 * via {@link Flight}'s {@code @DomainEvents} method (the Deployment-module
 * precedent). The flight-report read-model projector listens synchronously
 * (same transaction — ADR 0027 §2) and refreshes the affected
 * {@link FlightReportRow}s.
 *
 * <p>Publication rides the SAVE, not the application service, so every write
 * path — services, the showcase seeder's state transitions, integration tests
 * seeding via {@code repository.save} (ADR 0027 §3) — hits the projector.
 * Carries only the id: the projector re-loads the aggregate through the
 * tenant-scoped repository, so a soft-deleted flight surfaces as "absent" and
 * its read-model row is deleted.
 */
public record FlightSaved(UUID flightId) {

    public FlightSaved {
        if (flightId == null) {
            throw new IllegalArgumentException("flightId must not be null");
        }
    }
}
