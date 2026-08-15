package ch.alpenflight.referencedata.infra;

import ch.alpenflight.referencedata.domain.CounterUnitType;
import ch.alpenflight.referencedata.domain.CounterUnitTypeRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaCounterUnitTypeRepository
        extends JpaRepository<CounterUnitType, UUID>, CounterUnitTypeRepository {
}
