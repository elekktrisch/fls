package ch.alpenflight.referencedata.domain;

import java.util.List;
import java.util.UUID;

public interface LocationTypeRepository {

    List<LocationType> findAllByOrderByDescriptionAsc();

    boolean existsById(UUID id);
}
