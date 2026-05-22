package ch.alpenflight.referencedata.domain;

import java.util.List;

/**
 * Domain port for {@link CounterUnitType} reads. Read-only — rows are
 * Flyway-managed (V2 seed) and never written by the application.
 */
public interface CounterUnitTypeRepository {

    /** Returns all counter unit types, ordered by code. */
    List<CounterUnitType> findAllByOrderByCodeAsc();
}
