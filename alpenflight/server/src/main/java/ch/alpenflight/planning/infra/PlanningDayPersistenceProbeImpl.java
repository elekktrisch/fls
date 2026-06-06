package ch.alpenflight.planning.infra;

import ch.alpenflight.planning.domain.PlanningDay;
import ch.alpenflight.planning.domain.PlanningDayConflictException;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Custom-fragment impl backing {@link PlanningDayPersistenceProbe} (J-6 T-03).
 *
 * <p><strong>Dedup-aware save.</strong> {@link #saveDedup} persists + flushes
 * the day so the {@code ux_pln_club_date_loc} unique index fires synchronously;
 * a breach is caught and rethrown as the domain
 * {@link PlanningDayConflictException} (translated to 409 by the web layer,
 * T-04) — never a raw constraint-violation 500. This mirrors how the J-5
 * reservation conflict is surfaced as a catchable domain exception. The check
 * runs at the DB index (V4 partial-unique {@code WHERE deleted_on IS NULL}), so
 * a soft-deleted day never blocks re-creating the same {@code (club, date,
 * location)}.
 *
 * <p><strong>Per-day reservation count.</strong> {@link #countReservationsForDay}
 * is the one native SQL query (registered as
 * {@code planning-day-reservation-count} in {@code native-sql-register.md}): it
 * counts non-deleted {@code t_aircraft_reservation} rows for the caller's tenant
 * whose {@code date(reservation_start)} equals the planning date at the same
 * location. Hibernate's {@code @TenantId} discriminator does not filter native
 * SQL, so the query carries an explicit {@code operating_club_id = :tenantId}
 * predicate (resolved from the same {@link ClubTenantIdentifierResolver} the JPA
 * path uses, parameter-bound — never string-interpolated).
 */
class PlanningDayPersistenceProbeImpl implements PlanningDayPersistenceProbe {

    // date(reservation_start) compares the reservation's start DATE against the
    // pure-DATE planning_date; deleted_on IS NULL excludes soft-deleted rows —
    // mirrors the legacy NumberOfAircraftReservations (computed, never stored).
    private static final String COUNT_SQL = """
            SELECT count(*) FROM t_aircraft_reservation
            WHERE operating_club_id = :tenantId
              AND location_id = :locationId
              AND deleted_on IS NULL
              AND date(reservation_start) = :planningDate
            """;

    private final EntityManager entityManager;
    private final ClubTenantIdentifierResolver tenantResolver;
    private final TransactionTemplate tx;

    PlanningDayPersistenceProbeImpl(EntityManager entityManager,
                                    ClubTenantIdentifierResolver tenantResolver,
                                    TransactionTemplate tx) {
        this.entityManager = entityManager;
        this.tenantResolver = tenantResolver;
        this.tx = tx;
    }

    @Override
    public PlanningDay saveDedup(PlanningDay planningDay) {
        // Run inside an explicit transaction (joining the caller's when one is
        // active, REQUIRED) so the persist+flush has a bound JDBC session and
        // the unique-index breach surfaces here, catchable — mirroring the
        // FlightInitialState fragment's TransactionTemplate pattern.
        try {
            return tx.execute(status -> {
                PlanningDay managed;
                if (planningDay.getId() == null) {
                    entityManager.persist(planningDay);
                    managed = planningDay;
                } else {
                    managed = entityManager.merge(planningDay);
                }
                // Force the INSERT/UPDATE now so the unique-index breach surfaces
                // here (catchable) rather than at a later, untranslatable flush.
                entityManager.flush();
                return managed;
            });
        } catch (RuntimeException e) {
            // The breach may surface as the raw Hibernate PersistenceException
            // or as Spring's translated DataIntegrityViolationException
            // (depending on where the flush rolls back) — inspect the cause
            // chain for the ux_pln_club_date_loc constraint either way.
            if (isDuplicateDay(e)) {
                throw new PlanningDayConflictException(
                        "A planning day already exists for this club, date and location "
                                + "(ux_pln_club_date_loc)", e);
            }
            throw e;
        }
    }

    @Override
    public long countReservationsForDay(LocalDate planningDate, UUID locationId) {
        UUID tenantId = tenantResolver.resolveCurrentTenantIdentifier();
        Object result = entityManager.createNativeQuery(COUNT_SQL)
                .setParameter("tenantId", tenantId)
                .setParameter("locationId", locationId)
                .setParameter("planningDate", planningDate)
                .getSingleResult();
        return ((Number) result).longValue();
    }

    /** True iff {@code e} (or a cause) is the {@code ux_pln_club_date_loc} unique breach. */
    private static boolean isDuplicateDay(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && cve.getConstraintName() != null
                    && cve.getConstraintName().toLowerCase(java.util.Locale.ROOT)
                            .contains("ux_pln_club_date_loc")) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && msg.toLowerCase(java.util.Locale.ROOT)
                    .contains("ux_pln_club_date_loc")) {
                return true;
            }
        }
        return false;
    }
}
