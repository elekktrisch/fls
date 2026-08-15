package ch.alpenflight.accounting.infra;

import ch.alpenflight.accounting.domain.AccountingRuleFilter;
import ch.alpenflight.accounting.domain.AccountingRuleFilterRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaAccountingRuleFilterRepository
        extends JpaRepository<AccountingRuleFilter, UUID>, AccountingRuleFilterRepository {

    @Override
    @Query("select arf from AccountingRuleFilter arf where arf.deletedOn is null "
            + "order by arf.sortIndicator asc")
    List<AccountingRuleFilter> findAllActiveOrderedBySort();

    @Override
    @Query("select arf from AccountingRuleFilter arf where arf.deletedOn is null "
            + "order by arf.sortIndicator asc, arf.id asc")
    List<AccountingRuleFilter> findActiveForEngineOrdered();

    @Override
    @Query("select arf from AccountingRuleFilter arf "
            + "where arf.id = :id and arf.deletedOn is null")
    Optional<AccountingRuleFilter> findActiveById(@Param("id") UUID id);

    @Override
    @Query("select coalesce(max(arf.sortIndicator), -1) + 1 from AccountingRuleFilter arf "
            + "where arf.deletedOn is null")
    int nextSortIndicator();
}
