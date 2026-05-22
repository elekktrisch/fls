package ch.alpenflight.referencedata.domain;

import java.util.List;
import java.util.UUID;

/**
 * Domain port for {@link AircraftState} reads. Read-only — rows are
 * Flyway-managed (V3 seed) and never written by the application.
 */
public interface AircraftStateRepository {

    /** Returns all aircraft states, ordered by {@code legacy_int_id}. */
    List<AircraftState> findAllByOrderByLegacyIntIdAsc();

    /** True iff a row with the given id exists. */
    boolean existsById(UUID id);
}
