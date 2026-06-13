package ch.alpenflight.referencedata.infra;

import ch.alpenflight.referencedata.domain.AccountingRuleFilterType;
import ch.alpenflight.referencedata.domain.AccountingRuleFilterTypeRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data adapter for {@link AccountingRuleFilterTypeRepository}. V4-seeded; 8 rows. */
public interface JpaAccountingRuleFilterTypeRepository
        extends JpaRepository<AccountingRuleFilterType, UUID>, AccountingRuleFilterTypeRepository {
}
