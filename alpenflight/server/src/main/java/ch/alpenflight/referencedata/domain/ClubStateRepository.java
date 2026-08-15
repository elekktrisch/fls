package ch.alpenflight.referencedata.domain;

import java.util.List;
import java.util.UUID;

public interface ClubStateRepository {

    // RENAME: findAllOrdered -> findAllOrderedByNameUnderIcuCollation
    List<ClubState> findAllOrdered();

    boolean existsById(UUID id);
}
