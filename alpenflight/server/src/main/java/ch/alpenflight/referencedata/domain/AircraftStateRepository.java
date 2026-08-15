package ch.alpenflight.referencedata.domain;

import java.util.List;
import java.util.UUID;

public interface AircraftStateRepository {

    List<AircraftState> findAllByOrderByLegacyIntIdAsc();

    boolean existsById(UUID id);
}
