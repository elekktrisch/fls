package ch.alpenflight.referencedata.domain;

import java.util.List;
import java.util.UUID;

public interface CounterUnitTypeRepository {

    List<CounterUnitType> findAllByOrderByCodeAsc();

    boolean existsById(UUID id);
}
