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
        try {
            return tx.execute(status -> {
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
        return reservationCounts.countActiveOnDateAtLocation(planningDate, locationId);
    }

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
