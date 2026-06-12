package ch.alpenflight.server.testsupport;

import ch.alpenflight.locations.domain.Location;
import java.util.UUID;

/**
 * Minimal-object factory for {@link Location} that the S-024 leakage sweep
 * row builder consumes. Reads the first available country + location_type
 * reference rows from the seed (via the production reference-data
 * repositories); throws if either is missing (sweep is meaningless without
 * baseline reference data).
 *
 * <p>The factory deliberately does not seed reference data itself —
 * {@code V3__seed.sql} owns that, and a sweep that silently created its own
 * reference rows would mask a real seed regression. The returned Location is
 * transient; the consumer persists it through its own repository (the swept
 * aggregate under test).
 */
final class LocationSweepFactory {

    private LocationSweepFactory() {}

    static Location build(SweepFixtureContext ctx) {
        UUID countryId = ctx.firstCountryId();
        UUID locationTypeId = ctx.firstLocationTypeId();
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
}
