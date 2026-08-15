package ch.alpenflight.planning.infra;

import ch.alpenflight.planning.domain.PlanningDay;
import java.time.LocalDate;
import java.util.UUID;

interface PlanningDayPersistenceProbe {

    PlanningDay saveDedup(PlanningDay planningDay);

    long countReservationsForDay(LocalDate planningDate, UUID locationId);
}
