package ch.alpenflight.planning.infra;

import ch.alpenflight.planning.domain.PlanningDay;
import ch.alpenflight.planning.domain.PlanningDayConflictException;
import ch.alpenflight.reservations.api.ReservationCountPort;
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
 * (legacy {@code NumberOfAircraftReservations}) reads the figure through the
 * {@code reservations} module's {@link ReservationCountPort} named interface
 * (J-26 T-16) — {@code planning} no longer crosses into the
 * {@code t_aircraft_reservation} table itself. The retired
 * {@code planning-day-reservation-count} native-SQL probe (its register entry's
 * own "Remove when") is gone: the port is plain JPA over {@code
 * AircraftReservation}, tenant-filtered via Hibernate's {@code @TenantId}
 * discriminator (this runs in a resolved request tenant context).
 */
class PlanningDayPersistenceProbeImpl implements PlanningDayPersistenceProbe {

    private final EntityManager entityManager;
    private final ReservationCountPort reservationCounts;
    private final TransactionTemplate tx;

    PlanningDayPersistenceProbeImpl(EntityManager entityManager,
                                    ReservationCountPort reservationCounts,
                                    TransactionTemplate tx) {
        this.entityManager = entityManager;
        this.reservationCounts = reservationCounts;
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
        // Read the per-day reservation count through the reservations module's
        // named-interface port (J-26 T-16) instead of the retired native probe —
        // the port is plain JPA, tenant-filtered via @TenantId.
        return reservationCounts.countActiveOnDateAtLocation(planningDate, locationId);
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
