package ch.alpenflight.flights.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/**
 * Read-only projection over the {@code aircraft} table for the same-tenant
 * existence check that {@link ch.alpenflight.flights.application.FlightsService}
 * runs before persisting a Flight (per S-159 — {@code aircraft_id} is
 * same-tenant by construction; a write referencing a different-tenant
 * aircraft must surface as a 404, not silently create an unreadable row).
 *
 * <p>The {@code @TenantId} discriminator on {@code managing_club_id} is what
 * makes the existence check tenant-scoped — Hibernate filters the row away
 * for callers in a different tenant, so {@code findById} returns empty
 * exactly when "this aircraft belongs to another club" or "this aircraft
 * does not exist."
 *
 * <p>This projection lives in {@code flights.infra} rather than crossing
 * into {@code aircraft.domain} because the Flight module only needs a
 * tenant-aware existence check, not the full Aircraft aggregate (ADR 0023 —
 * minimise cross-module coupling).
 */
@Entity
@Table(name = "aircraft")
class AircraftTenantRefProjection {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private @Nullable UUID id;

    @TenantId
    @Column(name = "managing_club_id", nullable = false, updatable = false)
    private @Nullable UUID managingClubId;

    @Column(name = "deleted_on")
    private @Nullable Instant deletedOn;

    protected AircraftTenantRefProjection() {
        // JPA.
    }

    @Nullable UUID getId() {
        return id;
    }

    boolean isActive() {
        return deletedOn == null;
    }
}
