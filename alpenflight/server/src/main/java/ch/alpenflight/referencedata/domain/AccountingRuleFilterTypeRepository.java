package ch.alpenflight.referencedata.domain;

import java.util.List;

/**
 * Domain port for {@link AccountingRuleFilterType} reads. Read-only — rows are
 * Flyway-managed (V4 seed) and never written by the application.
 */
public interface AccountingRuleFilterTypeRepository {

    /** Returns all accounting-rule-filter types, ordered by legacy_int_id. */
    List<AccountingRuleFilterType> findAllByOrderByLegacyIntIdAsc();
}
