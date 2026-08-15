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

class AircraftOperatingCounterMapperTest
        extends AbstractMapperContractTest<AircraftOperatingCounterMapper> {

    private final AircraftOperatingCounterMapper mapper = new AircraftOperatingCounterMapper();

    @Override
    protected AircraftOperatingCounterMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("AircraftOperatingCounterId", randomUuidString(faker));
        row.put("AircraftId", randomUuidString(faker));
        row.put("AtDateTime", Timestamp.from(Instant.parse("2024-06-01T12:00:00Z")));
        row.put("TotalTowedGliderStarts", 1200);
        row.put("TotalWinchLaunchStarts", 50);
        row.put("TotalSelfStarts", 10);
        row.put("FlightOperatingCounterInSeconds", 36_000_000L);
        row.put("EngineOperatingCounterInSeconds", 18_000_000L);
        row.put("NextMaintenanceAtFlightOperatingCounterInSeconds", 100_000_000L);
        row.put("NextMaintenanceAtEngineOperatingCounterInSeconds", 50_000_000L);
        row.put("CreatedOn", Timestamp.from(Instant.parse("2024-06-01T12:00:00Z")));
        row.put("CreatedByUserId", randomUuidString(faker));
        row.put("ModifiedOn", Timestamp.from(Instant.parse("2024-07-01T12:00:00Z")));
        row.put("ModifiedByUserId", randomUuidString(faker));
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-08-01T12:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    @Test
    void exposesAircraftOperatingCounterEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.AIRCRAFT_OPERATING_COUNTER);
    }

    @Test
    void declaresAircraftAsTheOnlyStructuralForeignKey() {
        assertThat(mapper.foreignKeyTargets()).containsExactly(EntityType.AIRCRAFT);
    }
}
