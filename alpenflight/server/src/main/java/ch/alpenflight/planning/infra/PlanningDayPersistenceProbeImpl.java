package ch.alpenflight.planning.infra;

import ch.alpenflight.planning.domain.PlanningDay;
import ch.alpenflight.planning.domain.PlanningDayConflictException;
import ch.alpenflight.reservations.api.ReservationCountPort;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.transaction.support.TransactionTemplate;

class PlanningDayPersistenceProbeImpl implements PlanningDayPersistenceProbe {

    private static final String DUPLICATE_DAY_UNIQUE_INDEX = "ux_pln_club_date_loc";

    private final EntityManager entityManager;
    private final ReservationCountPort reservationCounts;
    private final TransactionTemplate joinOrStartTransaction;

    PlanningDayPersistenceProbeImpl(EntityManager entityManager,
                                    ReservationCountPort reservationCounts,
                                    TransactionTemplate joinOrStartTransaction) {
        this.entityManager = entityManager;
        this.reservationCounts = reservationCounts;
        this.joinOrStartTransaction = joinOrStartTransaction;
    }

    @Override
    public PlanningDay saveDedup(PlanningDay planningDay) {
        try {
            return joinOrStartTransaction.execute(status -> {
                PlanningDay managed;
                if (planningDay.getId() == null) {
                    entityManager.persist(planningDay);
                    managed = planningDay;
                } else {
                    managed = entityManager.merge(planningDay);
                }
                entityManager.flush();
                return managed;
            });
        } catch (RuntimeException e) {
            if (anyCauseBreachesDuplicateDayIndex(e)) {
                throw new PlanningDayConflictException(
                        "A planning day already exists for this club, date and location "
                                + "(" + DUPLICATE_DAY_UNIQUE_INDEX + ")", e);
            }
            throw e;
        }
    }

    @Override
    public long countReservationsForDay(LocalDate planningDate, UUID locationId) {
        return reservationCounts.countActiveOnDateAtLocation(planningDate, locationId);
    }

    private static boolean anyCauseBreachesDuplicateDayIndex(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && cve.getConstraintName() != null
                    && cve.getConstraintName().toLowerCase(java.util.Locale.ROOT)
                            .contains(DUPLICATE_DAY_UNIQUE_INDEX)) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && msg.toLowerCase(java.util.Locale.ROOT)
                    .contains(DUPLICATE_DAY_UNIQUE_INDEX)) {
                return true;
            }
        }
        return false;
    }
}
