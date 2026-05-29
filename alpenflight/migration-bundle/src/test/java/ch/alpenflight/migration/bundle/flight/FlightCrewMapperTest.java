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

class FlightCrewMapperTest extends AbstractMapperContractTest<FlightCrewMapper> {

    private final FlightCrewMapper mapper = new FlightCrewMapper();

    @Override
    protected FlightCrewMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("FlightCrewId", randomUuidString(faker));
        row.put("FlightId", randomUuidString(faker));
        row.put("PersonId", randomUuidString(faker));
        row.put("FlightCrewType", 1);
        row.put("BeginFlightDateTime", Timestamp.from(Instant.parse("2024-06-01T08:00:00Z")));
        row.put("EndFlightDateTime", Timestamp.from(Instant.parse("2024-06-01T10:00:00Z")));
        row.put("BeginInstructionDateTime",
                Timestamp.from(Instant.parse("2024-06-01T08:00:00Z")));
        row.put("EndInstructionDateTime",
                Timestamp.from(Instant.parse("2024-06-01T10:00:00Z")));
        row.put("NrOfLdgs", (short) 2);
        row.put("NrOfStarts", (short) 1);
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-06-04T00:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    @Test
    void exposesFlightCrewEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.FLIGHT_CREW);
    }

    @Test
    void declaresFlightAndPersonAsStructuralForeignKeys() {
        assertThat(mapper.foreignKeys())
                .as("flight_id is intra-aggregate to Flight; person_id rides "
                        + "TENANT_BYPASS_ALLOW_LIST to cross-tenant Person")
                .containsExactlyInAnyOrder(EntityType.FLIGHT, EntityType.PERSON);
    }
}
