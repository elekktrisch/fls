package ch.alpenflight.referencedata.domain;

import java.util.List;

public interface AccountingRuleFilterTypeRepository {

    List<AccountingRuleFilterType> findAllByOrderByLegacyIntIdAsc();
}
