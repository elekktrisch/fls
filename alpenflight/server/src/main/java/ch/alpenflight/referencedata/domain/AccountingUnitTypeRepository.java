package ch.alpenflight.referencedata.domain;

import java.util.List;

/**
 * Domain port for {@link AccountingUnitType} reads. Read-only — rows are
 * Flyway-managed (V4 seed) and never written by the application.
 */
public interface AccountingUnitTypeRepository {

    /** Returns all accounting-unit types, ordered by legacy_int_id. */
    List<AccountingUnitType> findAllByOrderByLegacyIntIdAsc();
}
