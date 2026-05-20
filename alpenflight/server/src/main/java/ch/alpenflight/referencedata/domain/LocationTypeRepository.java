package ch.alpenflight.referencedata.domain;

import java.util.List;
import java.util.UUID;

/**
 * Domain port for {@link LocationType} reads. Implemented by
 * {@code ch.alpenflight.referencedata.infra.JpaLocationTypeRepository}.
 *
 * <p>Read-only by design — LocationType rows are Flyway-managed and never
 * written by the application. LocationType admin UI is deferred per
 * S-049 acceptance.
 */
public interface LocationTypeRepository {

    /** Returns all location types, ordered by {@code description}. */
    List<LocationType> findAllByOrderByDescriptionAsc();

    /** True iff a row with the given id exists. */
    boolean existsById(UUID id);
}
