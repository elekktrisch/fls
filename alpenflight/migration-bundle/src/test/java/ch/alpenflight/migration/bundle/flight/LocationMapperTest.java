package ch.alpenflight.migration.bundle.flight;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class LocationMapperTest extends AbstractMapperContractTest<LocationMapper> {

    private final LocationMapper mapper = new LocationMapper();

    @Override
    protected LocationMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("LocationId", randomUuidString(faker));
        // ClubId comes from the producer's per-Club fan-out — the legacy
        // table has no club ownership; producer-side SELECT aliases the
        // fan-out partner Club's id as ClubId on the cursor.
        row.put("ClubId", randomUuidString(faker));
        row.put("LocationName", faker.address().cityName() + " " + faker.aviation().airport());
        row.put("LocationShortName", faker.address().cityName());
        row.put("CountryId", randomUuidString(faker));
        row.put("LocationTypeId", 2);
        row.put("IcaoCode", "LS" + faker.regexify("[A-Z]{2}"));
        row.put("Latitude", "47.46");
        row.put("Longitude", "8.55");
        row.put("Elevation", 432);
        row.put("ElevationUnitType", 1);
        row.put("RunwayDirection", "14/32");
        row.put("RunwayLength", 1200);
        row.put("RunwayLengthUnitType", 2);
        row.put("AirportFrequency", "126.700");
        row.put("Description", faker.lorem().sentence());
        row.put("SortIndicator", 10);
        row.put("IsInboundRouteRequired", false);
        row.put("IsOutboundRouteRequired", true);
        row.put("IsFastEntryRecord", false);
        row.put("CreatedOn", Timestamp.from(Instant.parse("2024-01-01T12:00:00Z")));
        row.put("CreatedByUserId", randomUuidString(faker));
        row.put("ModifiedOn", Timestamp.from(Instant.parse("2024-02-01T12:00:00Z")));
        row.put("ModifiedByUserId", randomUuidString(faker));
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-03-01T12:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    @Test
    void exposesLocationEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.LOCATION);
    }

    @Test
    void declaresClubAndCountryAsStructuralForeignKeys() {
        assertThat(mapper.foreignKeys())
                .as("Location is tenant-scoped via club_id per V7; FK to "
                        + "Country is SYSTEM_GLOBAL — both EntityType members "
                        + "must precede LOCATION in the topo order")
                .containsExactlyInAnyOrder(EntityType.CLUB, EntityType.COUNTRY);
    }
}
