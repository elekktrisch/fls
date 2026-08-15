package ch.alpenflight.accounting.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountingRuleFilterRepository {

    List<AccountingRuleFilter> findAllActiveOrderedBySort();

    List<AccountingRuleFilter> findActiveForEngineOrdered();

    Optional<AccountingRuleFilter> findActiveById(UUID id);

    int nextSortIndicator();

    AccountingRuleFilter save(AccountingRuleFilter filter);

    void flush();
}
