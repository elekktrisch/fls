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

class AircraftAircraftStateMapperTest
        extends AbstractMapperContractTest<AircraftAircraftStateMapper> {

    private final AircraftAircraftStateMapper mapper = new AircraftAircraftStateMapper();

    @Override
    protected AircraftAircraftStateMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("AircraftId", randomUuidString(faker));
        row.put("AircraftState", 1);
        row.put("ValidFrom", Timestamp.from(Instant.parse("2023-01-01T00:00:00Z")));
        row.put("ValidTo", Timestamp.from(Instant.parse("2024-01-01T00:00:00Z")));
        row.put("NoticedByPersonId", randomUuidString(faker));
        row.put("Remarks", faker.lorem().sentence());
        row.put("CreatedOn", Timestamp.from(Instant.parse("2023-01-01T12:00:00Z")));
        row.put("CreatedByUserId", randomUuidString(faker));
        row.put("ModifiedOn", Timestamp.from(Instant.parse("2024-02-01T12:00:00Z")));
        row.put("ModifiedByUserId", randomUuidString(faker));
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-03-01T12:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    @Test
    void exposesAircraftAircraftStateEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.AIRCRAFT_AIRCRAFT_STATE);
    }

    @Test
    void declaresAircraftAsTheOnlyStructuralForeignKey() {
        assertThat(mapper.foreignKeyTargets())
                .as("aircraft_state_id resolves through V3 seeded legacy_int_id "
                        + "map — not declared. noticed_by_person_id is in the "
                        + "TENANT_BYPASS_ALLOW_LIST set, not foreignKeys.")
                .containsExactly(EntityType.AIRCRAFT);
    }
}
