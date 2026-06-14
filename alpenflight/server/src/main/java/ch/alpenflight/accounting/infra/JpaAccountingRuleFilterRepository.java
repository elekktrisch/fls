package ch.alpenflight.accounting.infra;

import ch.alpenflight.accounting.domain.AccountingRuleFilter;
import ch.alpenflight.accounting.domain.AccountingRuleFilterRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA implementation of {@link AccountingRuleFilterRepository}.
 * Extends both the abstract port and {@code JpaRepository<AccountingRuleFilter,
 * UUID>} so the application layer depends on the port (ADR 0023) while Spring
 * Data generates the runtime bean.
 *
 * <p>AccountingRuleFilter is tenant-scoped via {@code @TenantId} on
 * {@code AccountingRuleFilter.operatingClubId}; Hibernate appends the tenant
 * predicate to every JPQL query (read + aggregate) automatically, so none of
 * these queries name {@code operatingClubId} — it is the discriminator, not a
 * parameter. Soft-delete is filtered at the query layer
 * ({@code deletedOn is null}). JPA-first per ADR 0027 — no native SQL.
 */
public interface JpaAccountingRuleFilterRepository
        extends JpaRepository<AccountingRuleFilter, UUID>, AccountingRuleFilterRepository {

    @Override
    @Query("select arf from AccountingRuleFilter arf where arf.deletedOn is null "
            + "order by arf.sortIndicator asc")
    List<AccountingRuleFilter> findAllActiveOrderedBySort();

    @Override
    @Query("select arf from AccountingRuleFilter arf "
            + "where arf.id = :id and arf.deletedOn is null")
    Optional<AccountingRuleFilter> findActiveById(@Param("id") UUID id);

    @Override
    @Query("select coalesce(max(arf.sortIndicator), -1) + 1 from AccountingRuleFilter arf "
            + "where arf.deletedOn is null")
    int nextSortIndicator();
}
