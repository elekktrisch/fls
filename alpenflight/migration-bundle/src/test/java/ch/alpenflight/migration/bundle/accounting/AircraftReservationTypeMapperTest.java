package ch.alpenflight.migration.bundle.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class AircraftReservationTypeMapperTest
        extends AbstractMapperContractTest<AircraftReservationTypeMapper> {

    private final AircraftReservationTypeMapper mapper = new AircraftReservationTypeMapper();

    @Override
    protected AircraftReservationTypeMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("AircraftReservationTypeId", randomUuidString(faker));
        row.put("ClubId", randomUuidString(faker));
        row.put("AircraftReservationTypeName", "Standard");
        row.put("IsInstructorRequired", false);
        row.put("IsMaintenance", false);
        row.put("IsActive", true);
        row.put("Remarks", faker.lorem().sentence());
        row.put("CreatedOn", Timestamp.from(Instant.parse("2024-01-01T12:00:00Z")));
        row.put("CreatedByUserId", randomUuidString(faker));
        row.put("ModifiedOn", Timestamp.from(Instant.parse("2024-02-01T12:00:00Z")));
        row.put("ModifiedByUserId", randomUuidString(faker));
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-03-01T12:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    @Test
    void exposesAircraftReservationTypeEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.AIRCRAFT_RESERVATION_TYPE);
    }

    @Test
    void declaresOnlyClubAsForeignKey() {
        assertThat(mapper.foreignKeys()).containsExactly(EntityType.CLUB);
    }
}
