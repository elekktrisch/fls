package ch.alpenflight.multitenancy.leakage;

import ch.alpenflight.locations.application.LocationDtos.LocationCreateRequest;
import ch.alpenflight.locations.application.LocationDtos.LocationDetail;
import ch.alpenflight.locations.application.LocationsService;
import ch.alpenflight.platform.id.CountryId;
import ch.alpenflight.platform.id.LocationTypeId;
import ch.alpenflight.server.testsupport.TenantTestContext;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Witness implementation of {@link CrossTenantNotFoundContract} against the
 * {@link LocationsService} surface (the {@code /api/v1/locations} controller).
 * Today's only tenant-scoped controller — when S-046 (Aircraft admin) and
 * future tenant-scoped controllers land, they extend the contract and the
 * IDOR-gate witness propagates without a per-controller test rewrite.
 */
class LocationsCrossTenantNotFoundIT extends CrossTenantNotFoundContract {

    @Autowired private LocationsService locations;

    @Override
    protected String pathToReadById(String externalId) {
        return "/api/v1/locations/" + externalId;
    }

    @Override
    protected String createUnderTenant(UUID clubId) {
        UUID countryId = jdbc.queryForObject("SELECT id FROM country LIMIT 1", UUID.class);
        UUID typeId = jdbc.queryForObject("SELECT id FROM location_type LIMIT 1", UUID.class);
        String unique = Long.toString(System.nanoTime(), 36);
        LocationCreateRequest req = new LocationCreateRequest(
                "IT_XTNF_LOC_" + unique,
                null,
                CountryId.of(countryId),
                LocationTypeId.of(typeId),
                null,
                null, null,
                null, null,
                null, null, null,
                null,
                null,
                null,
                false, false, false,
                List.of());

        LocationDetail created = TenantTestContext.runAs(clubId, () -> locations.createLocation(req));
        return created.id().toString();
    }
}
