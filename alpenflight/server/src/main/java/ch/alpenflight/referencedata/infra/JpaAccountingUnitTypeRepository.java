package ch.alpenflight.referencedata.infra;

import ch.alpenflight.referencedata.domain.AccountingUnitType;
import ch.alpenflight.referencedata.domain.AccountingUnitTypeRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaAccountingUnitTypeRepository
        extends JpaRepository<AccountingUnitType, UUID>, AccountingUnitTypeRepository {
}
