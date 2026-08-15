package ch.alpenflight.referencedata.domain;

import java.util.List;

public interface AccountingUnitTypeRepository {

    List<AccountingUnitType> findAllByOrderByLegacyIntIdAsc();
}
