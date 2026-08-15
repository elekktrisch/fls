package ch.alpenflight.planning.infra;

import ch.alpenflight.planning.domain.PlanningDay;
import ch.alpenflight.planning.domain.PlanningDayRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.jspecify.annotations.Nullable;

public interface JpaPlanningDayRepository
        extends JpaRepository<PlanningDay, UUID>,
                PlanningDayRepository,
                PlanningDayPersistenceProbe {

    String FUTURE_SELECT =
            "select new ch.alpenflight.planning.domain.PlanningDayRepository$ListRow("
                    + "d.id, d.planningDate, d.locationId, d.info) "
                    + "from PlanningDay d "
                    + "where d.deletedOn is null and d.planningDate >= :asOf ";

    @Override
    default PlanningDay save(PlanningDay planningDay) {
        return saveDedup(planningDay);
    }

    @Override
    @Query("select d from PlanningDay d where d.id = :id and d.deletedOn is null")
    Optional<PlanningDay> findActiveById(@Param("id") UUID id);

    @Override
    @Query("select count(d) > 0 from PlanningDay d "
            + "where d.deletedOn is null and d.planningDate = :planningDate "
            + "and d.locationId = :locationId")
    boolean existsActiveForDay(@Param("planningDate") LocalDate planningDate,
                               @Param("locationId") UUID locationId);

    @Override
    @Query("select count(d) > 0 from PlanningDay d "
            + "where d.deletedOn is null and d.planningDate = :planningDate "
            + "and d.locationId = :locationId "
            + "and (:excludeId is null or d.id <> :excludeId)")
    boolean existsActiveForDayExcluding(@Param("planningDate") LocalDate planningDate,
                                        @Param("locationId") UUID locationId,
                                        @Param("excludeId") @Nullable UUID excludeId);

    @Override
    default Page findFuturePage(LocalDate asOf, int pageStart, int pageSize) {
        if (pageSize <= 0) {
            return new Page(List.of(), pageStart, pageSize, 0L);
        }
        Pageable page = PageRequest.of(pageStart / pageSize, pageSize);
        List<ListRow> items = findFuturePageRows(asOf, page);
        long total = countFuture(asOf);
        return new Page(items, pageStart, pageSize, total);
    }

    @Query(FUTURE_SELECT + "order by d.planningDate asc, d.id asc")
    List<ListRow> findFuturePageRows(@Param("asOf") LocalDate asOf, Pageable pageable);

    @Query("select count(d) from PlanningDay d "
            + "where d.deletedOn is null and d.planningDate >= :asOf")
    long countFuture(@Param("asOf") LocalDate asOf);

    @Override
    @Query(FUTURE_SELECT + "order by d.planningDate asc, d.id asc")
    List<ListRow> findFutureListRows(@Param("asOf") LocalDate asOf);

    @Override
    @Query("select d from PlanningDay d "
            + "where d.deletedOn is null and d.planningDate = :planningDate "
            + "order by d.id asc")
    List<PlanningDay> findActiveByDate(@Param("planningDate") LocalDate planningDate);
}
