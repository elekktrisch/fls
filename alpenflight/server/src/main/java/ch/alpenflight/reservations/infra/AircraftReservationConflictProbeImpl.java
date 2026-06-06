package ch.alpenflight.reservations.infra;

import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import ch.alpenflight.reservations.domain.AircraftReservationRepository.Range;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Custom-fragment impl backing {@link AircraftReservationConflictProbe} — the
 * GiST range-overlap conflict probe (J-5 T-04). The one native SQL query on the
 * tenant-scoped {@code t_aircraft_reservation} table, registered in
 * {@code native-sql-register.md} as {@code reservations-conflict-gist-overlap-probe}.
 *
 * <p>Why native: the half-open overlap test uses the Postgres {@code &&}
 * range-overlap operator against the generated {@code reservation_range
 * tstzrange} column so the partial GiST index
 * {@code ix_arv_aircraft_range_gist} (on {@code (aircraft_id, reservation_range)
 * WHERE deleted_on IS NULL}) serves the probe in sub-10ms — neither the
 * operator nor the generated range column is expressible in JPQL.
 *
 * <p><strong>Tenancy gate.</strong> Hibernate's {@code @TenantId} discriminator
 * does NOT filter native SQL, so the query carries an explicit
 * {@code operating_club_id = :tenantId} predicate. The tenant id is resolved
 * from the same {@link ClubTenantIdentifierResolver} the JPA path uses (JWT →
 * {@code Tenants.runAs} carrier precedence) and parameter-bound — never
 * caller-controlled string interpolation. Soft-deleted rows are excluded
 * ({@code deleted_on IS NULL}); the row being edited is self-excluded
 * ({@code :excludeId IS NULL OR id <> :excludeId}).
 */
class AircraftReservationConflictProbeImpl implements AircraftReservationConflictProbe {

    // CAST(:excludeId AS uuid): the parameter is NULL on create, and Postgres
    // can't infer a NULL bind's type from `IS NULL` / `<>` alone — an explicit
    // cast pins it to uuid so the prepared statement plans (avoids
    // "could not determine data type of parameter $n").
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
