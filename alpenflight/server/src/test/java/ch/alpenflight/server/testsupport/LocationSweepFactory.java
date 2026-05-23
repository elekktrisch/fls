package ch.alpenflight.server.testsupport;

import ch.alpenflight.locations.domain.Location;
import jakarta.persistence.EntityManager;
import java.util.UUID;

/**
 * Minimal-object factory for {@link Location} that the S-024 leakage sweep
 * row builder consumes. Looks up the first available country + location_type
 * rows from the seed; throws if either is missing (sweep is meaningless
 * without baseline reference data).
 *
 * <p>The factory deliberately does not seed reference data itself —
 * {@code V3__seed.sql} owns that, and a sweep that silently created its own
 * reference rows would mask a real seed regression.
 */
final class LocationSweepFactory {

    private LocationSweepFactory() {}

    static Location build(EntityManager em) {
        UUID countryId = firstId(em, "country");
        UUID locationTypeId = firstId(em, "location_type");
        String unique = Long.toString(System.nanoTime(), 36);
        return Location.create(
                TenantScopedRowBuilders.SWEEP_PREFIX + "LOC_" + unique,
                null,
                countryId,
                locationTypeId,
                null,
                null, null,
                null, null,
                null, null, null,
                null,
                null,
                null,
                false, false, false);
    }

    private static UUID firstId(EntityManager em, String table) {
        Object result = em.createNativeQuery("SELECT id FROM " + table + " LIMIT 1")
                .getSingleResultOrNull();
        if (result == null) {
            throw new IllegalStateException(
                    "No row in " + table + " — V3 seed must populate at least one reference row");
        }
        return (UUID) result;
    }
}
