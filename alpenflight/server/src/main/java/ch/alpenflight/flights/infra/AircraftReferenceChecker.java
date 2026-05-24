package ch.alpenflight.flights.infra;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Helper exposing the tenant-aware "does this aircraft exist under the
 * caller's tenant?" check. Used by {@code FlightsService} on create /
 * update to enforce the S-159 same-tenant aircraft contract before
 * persisting.
 */
@Component
public class AircraftReferenceChecker {

    private final EntityManager em;

    public AircraftReferenceChecker(EntityManager em) {
        this.em = em;
    }

    /**
     * Returns {@code true} when an active (non-deleted) Aircraft with the
     * given id exists under the caller's tenant. Returns {@code false} when
     * the aircraft does not exist or belongs to another tenant (Hibernate
     * {@code @TenantId} hides the row in the latter case).
     */
    public boolean isAccessibleAircraft(UUID aircraftId) {
        AircraftTenantRefProjection row = em.find(AircraftTenantRefProjection.class, aircraftId);
        return row != null && row.isActive();
    }
}
