package ch.alpenflight.referencedata.domain;

import java.util.List;
import java.util.UUID;

/**
 * Domain port for {@link AircraftType} reads. Read-only — rows are
 * Flyway-managed (V3 seed) and never written by the application.
 */
public interface AircraftTypeRepository {

    /** Returns all aircraft types, ordered by {@code legacy_int_id}. */
    List<AircraftType> findAllByOrderByLegacyIntIdAsc();

    /** True iff a row with the given id exists. */
    boolean existsById(UUID id);
}
