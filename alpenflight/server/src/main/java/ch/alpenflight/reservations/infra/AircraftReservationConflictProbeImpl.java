package ch.alpenflight.reservations.infra;

import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import ch.alpenflight.reservations.domain.AircraftReservationRepository.Range;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

class AircraftReservationConflictProbeImpl implements AircraftReservationConflictProbe {

    private static final String CONFLICT_SQL = """
            SELECT EXISTS (
              SELECT 1 FROM t_aircraft_reservation
              WHERE operating_club_id = :tenantId
                AND aircraft_id = :aircraftId
                AND deleted_on IS NULL
                AND reservation_range && tstzrange(:windowStart, :windowEnd, '[)')
                AND (CAST(:excludeId AS uuid) IS NULL OR id <> CAST(:excludeId AS uuid))
            )
            """;

    private final EntityManager entityManager;
    private final ClubTenantIdentifierResolver tenantResolver;

    AircraftReservationConflictProbeImpl(EntityManager entityManager,
                                         ClubTenantIdentifierResolver tenantResolver) {
        this.entityManager = entityManager;
        this.tenantResolver = tenantResolver;
    }

    @Override
    public boolean existsActiveConflict(UUID aircraftId, Range window, @Nullable UUID excludeId) {
        UUID tenantId = tenantResolver.resolveCurrentTenantIdentifier();
        Object result = entityManager.createNativeQuery(CONFLICT_SQL)
                .setParameter("tenantId", tenantId)
                .setParameter("aircraftId", aircraftId)
                .setParameter("windowStart", window.start())
                .setParameter("windowEnd", window.end())
                .setParameter("excludeId", excludeId)
                .getSingleResult();
        return Boolean.TRUE.equals(result);
    }
}
