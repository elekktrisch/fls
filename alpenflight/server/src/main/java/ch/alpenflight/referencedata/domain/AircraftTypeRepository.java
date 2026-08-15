package ch.alpenflight.referencedata.domain;

import java.util.List;
import java.util.UUID;

public interface AircraftTypeRepository {

    List<AircraftType> findAllByOrderByLegacyIntIdAsc();

    boolean existsById(UUID id);
}
