package ch.alpenflight.referencedata.domain;

import java.util.List;

/**
 * Domain port for {@link FlightCrewType} reads. Read-only — rows are
 * Flyway-managed (V3 seed) and never written by the application.
 */
public interface FlightCrewTypeRepository {

    /** Returns all flight-crew types, ordered by legacy_int_id. */
    List<FlightCrewType> findAllByOrderByLegacyIntIdAsc();
}
