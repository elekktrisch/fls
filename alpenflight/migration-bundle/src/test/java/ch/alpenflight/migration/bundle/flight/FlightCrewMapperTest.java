package ch.alpenflight.migration.bundle.flight;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityType;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    // FLIGHT-FIDELITY: crew survives migration. Each legacy assignment is one
    // FlightCrew row keyed by (flight_id, person_id, flight_crew_type_id); the
    // role code (FlightCrewType.cs) is carried as the V3-seeded reference UUID
    // new UUID(0, code). A round-trip over every legitimate role proves a per-
    // person crew row reaches the ingest with its role intact — the assertion
    // the slow fanout otherwise gates exclusively.
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 10})
    void crewRowRoundTripsPersonKeyedByItsRoleCode(int legacyCrewType) throws Exception {
        Faker faker = seededFaker();
        Map<String, Object> row = legacyRow(faker);
        UUID flightId = UUID.fromString((String) row.get("FlightId"));
        UUID personId = UUID.fromString((String) row.get("PersonId"));
        row.put("FlightCrewType", legacyCrewType);

        JsonNode emitted = invokeWriteNdjson(mapper, row);

        assertThat(UUID.fromString(emitted.get(FlightCrewMapper.FLIGHT_ID).asText()))
                .as("crew row stays keyed to its flight")
                .isEqualTo(flightId);
        assertThat(UUID.fromString(emitted.get(FlightCrewMapper.PERSON_ID).asText()))
                .as("crew row keeps its assigned person (cross-tenant Person FK)")
                .isEqualTo(personId);
        assertThat(emitted.get(FlightCrewMapper.FLIGHT_CREW_TYPE_ID).asText())
                .as("role code %d carries as the V3-seeded reference UUID new UUID(0, code)",
                        legacyCrewType)
                .isEqualTo(Coercions.legacyIntIdToUuidString(legacyCrewType));
    }

    @Test
    void distinctRolesYieldDistinctCrewTypeReferences() throws Exception {
        Faker faker = seededFaker();
        Map<String, Object> pilotRow = legacyRow(faker);
        pilotRow.put("FlightCrewType", 1);
        Map<String, Object> instructorRow = legacyRow(faker);
        instructorRow.put("FlightCrewType", 3);

        String pilotType =
                invokeWriteNdjson(mapper, pilotRow).get(FlightCrewMapper.FLIGHT_CREW_TYPE_ID).asText();
        String instructorType =
                invokeWriteNdjson(mapper, instructorRow)
                        .get(FlightCrewMapper.FLIGHT_CREW_TYPE_ID).asText();

        assertThat(pilotType)
                .as("a Pilot and an Instructor on the same flight must NOT collapse "
                        + "to one crew-type reference — the Schulung pair's role split survives")
                .isNotEqualTo(instructorType);
    }
}
