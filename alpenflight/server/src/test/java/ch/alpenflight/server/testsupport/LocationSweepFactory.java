package ch.alpenflight.server.testsupport;

import ch.alpenflight.locations.domain.Location;
import java.util.UUID;

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
