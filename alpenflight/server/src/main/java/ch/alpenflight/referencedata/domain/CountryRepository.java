package ch.alpenflight.referencedata.domain;

import java.util.List;
import java.util.UUID;

/**
 * Domain port for {@link Country} reads. Implemented by
 * {@code ch.alpenflight.referencedata.infra.JpaCountryRepository}.
 *
 * <p>Read-only by design — Country rows are Flyway-managed and never
 * written by the application.
 */
public interface CountryRepository {

    /** Returns all countries, ordered by {@code name} under ICU {@code de-CH} collation. */
    List<Country> findAllOrdered();

    /** True iff a row with the given id exists. */
    boolean existsById(UUID id);
}
