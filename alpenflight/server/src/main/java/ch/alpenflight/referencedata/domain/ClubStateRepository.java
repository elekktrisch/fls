package ch.alpenflight.referencedata.domain;

import java.util.List;
import java.util.UUID;

public interface ClubStateRepository {

    List<ClubState> findAllOrderedByNameUnderIcuCollation();

    boolean existsById(UUID id);
}
