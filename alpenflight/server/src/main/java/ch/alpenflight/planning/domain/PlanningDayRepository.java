package ch.alpenflight.planning.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface PlanningDayRepository {

    record ListRow(UUID id,
                   LocalDate planningDate,
                   UUID locationId,
                   @Nullable String info) {}

    record Page(List<ListRow> items, int pageStart, int pageSize, long totalRows) {}

    PlanningDay save(PlanningDay planningDay);

    Optional<PlanningDay> findActiveById(UUID id);

    boolean existsActiveForDay(LocalDate planningDate, UUID locationId);

    boolean existsActiveForDayExcluding(LocalDate planningDate, UUID locationId,
                                        @Nullable UUID excludeId);

    Page findFuturePage(LocalDate asOf, int pageStart, int pageSize);

    List<ListRow> findFutureListRows(LocalDate asOf);

    List<PlanningDay> findActiveByDate(LocalDate planningDate);

    long countReservationsForDay(LocalDate planningDate, UUID locationId);

    void flush();
}
