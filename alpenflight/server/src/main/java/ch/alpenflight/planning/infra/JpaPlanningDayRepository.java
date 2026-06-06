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

/**
 * Spring Data JPA implementation of the {@link PlanningDayRepository} domain
 * port (J-6 T-03). Composes {@code JpaRepository<PlanningDay, UUID>}, the port,
 * and the {@link PlanningDayPersistenceProbe} custom fragment (the dedup-aware
 * save + the native per-day reservation count, which both need an injected
 * {@code EntityManager}/tenant resolver a default method can't reach). The
 * application layer depends only on the port (ADR 0023).
 *
 * <p><strong>Tenancy.</strong> {@code t_planning_day} is tenant-scoped via
 * Hibernate's {@code @TenantId} discriminator on {@code operating_club_id}
 * (ADR 0008). The JPQL list/find queries inherit the tenant predicate
 * automatically (cross-tenant rows invisible → GET 404). The reservation-count
 * native query is the exception — it carries an explicit tenant predicate (see
 * {@link PlanningDayPersistenceProbeImpl}, registered in
 * {@code native-sql-register.md}).
 *
 * <p><strong>Soft-delete.</strong> Every read filters {@code deleted_on IS
 * NULL}. The {@code ux_pln_club_date_loc} unique index is itself partial
 * ({@code WHERE deleted_on IS NULL}, V4), so a soft-deleted day never blocks
 * re-creating the same {@code (club, date, location)} tuple.
 *
 * <p>List rows are flat {@link PlanningDayRepository.ListRow} projections (the
 * SPA decorates display labels + resolves the 3 well-known crew roles from the
 * generic assignment rows at the service layer) — no cross-module joins (ADR
 * 0023), mirroring the J-5 reservation {@code ListRow} convention.
 */
public interface JpaPlanningDayRepository
        extends JpaRepository<PlanningDay, UUID>,
                PlanningDayRepository,
                PlanningDayPersistenceProbe {

    /** Base projection + tenant-implicit (@TenantId) + soft-delete + future filter. */
    String FUTURE_SELECT =
            "select new ch.alpenflight.planning.domain.PlanningDayRepository$ListRow("
                    + "d.id, d.planningDate, d.locationId, d.info) "
                    + "from PlanningDay d "
                    + "where d.deletedOn is null and d.planningDate >= :asOf ";

    /** Dedup-aware save: routes through the fragment so the unique breach is catchable. */
    @Override
    default PlanningDay save(PlanningDay planningDay) {
        return saveDedup(planningDay);
    }

    @Override
    @Query("select d from PlanningDay d where d.id = :id and d.deletedOn is null")
    Optional<PlanningDay> findActiveById(@Param("id") UUID id);

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
}
