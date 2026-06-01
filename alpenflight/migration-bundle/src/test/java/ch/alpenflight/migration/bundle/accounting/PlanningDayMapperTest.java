package ch.alpenflight.migration.bundle.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class PlanningDayMapperTest extends AbstractMapperContractTest<PlanningDayMapper> {

    private final PlanningDayMapper mapper = new PlanningDayMapper();

    @Override
    protected PlanningDayMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("PlanningDayId", randomUuidString(faker));
        row.put("ClubId", randomUuidString(faker));
        row.put("Day", Date.valueOf("2024-06-15"));
        row.put("LocationId", randomUuidString(faker));
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
    void exposesPlanningDayEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.PLANNING_DAY);
    }

    @Test
    void declaresClubAndLocationAsForeignKeys() {
        // Location is tenant-scoped via composite legacy_id_map_location replica
        // (S-185 pattern); no tenant bypass for PlanningDay itself.
        assertThat(mapper.foreignKeys())
                .containsExactly(EntityType.CLUB, EntityType.LOCATION);
    }
}
