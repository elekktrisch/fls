package ch.alpenflight.referencedata.domain;

import java.util.List;
import java.util.UUID;

/**
 * Domain port for {@link ClubState} reads. Implemented by
 * {@code ch.alpenflight.referencedata.infra.JpaClubStateRepository}.
 */
public interface ClubStateRepository {

    /** Returns all club states, ordered by {@code name} under ICU {@code de-CH} collation. */
    List<ClubState> findAllOrdered();

    /** True iff a row with the given id exists. */
    boolean existsById(UUID id);
}
