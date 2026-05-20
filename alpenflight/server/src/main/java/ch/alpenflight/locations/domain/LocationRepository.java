package ch.alpenflight.locations.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Domain port for {@link Location} persistence. Implemented by
 * {@code ch.alpenflight.locations.infra.JpaLocationRepository}.
 *
 * <p>Soft-delete is encoded in the JPQL of the JPA adapter; this interface
 * speaks the same "active rows only" contract.
 */
public interface LocationRepository {

    /**
     * Projection row for the {@code GET /api/v1/locations} list view. Carries
     * the LocationType code + isAirfield flag pulled in by JOIN so the list
     * endpoint serves in a single SQL round-trip (N+1 guard locked by
     * {@code LocationsN1GuardIT}).
     */
    record ListRow(UUID id,
                   String locationName,
                   @Nullable String locationShortName,
                   @Nullable String icaoCode,
                   String locationTypeCode,
                   boolean isAirfield,
                   boolean isFastEntryRecord) {}

    /**
     * Returns active (non-soft-deleted) locations ordered by
     * {@code sort_indicator ASC NULLS LAST, location_name ASC}, joined with
     * {@code location_type} to surface the type code + airfield flag on a
     * single row. Single SQL statement; no per-row IOP fetch.
     */
    List<ListRow> findAllActiveListRows();

    /**
     * Returns the active Location with the given id together with its full
     * {@code inOutboundPoints} collection (entity-graph fetch — single SQL
     * round-trip).
     */
    Optional<Location> findActiveByIdWithPoints(UUID id);

    /** Returns the active Location with the given id (no IOP fetch). */
    Optional<Location> findActiveById(UUID id);

    /** Case-insensitive lookup of an active Location by ICAO code, if any. */
    Optional<Location> findActiveByIcaoCodeIgnoreCase(String icaoCode);

    /** Persist (insert or update). Returns the managed entity. */
    Location save(Location location);
}
