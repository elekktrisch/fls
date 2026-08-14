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

class PlanningDayAssignmentMapperTest
        extends AbstractMapperContractTest<PlanningDayAssignmentMapper> {

    private final PlanningDayAssignmentMapper mapper = new PlanningDayAssignmentMapper();

    @Override
    protected PlanningDayAssignmentMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("PlanningDayAssignmentId", randomUuidString(faker));
        row.put("OperatingClubId", randomUuidString(faker));
        row.put("AssignedPlanningDayId", randomUuidString(faker));
        row.put("AssignedPersonId", randomUuidString(faker));
        row.put("AssignmentTypeId", randomUuidString(faker));
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
    void exposesPlanningDayAssignmentEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.PLANNING_DAY_ASSIGNMENT);
    }

    @Test
    void declaresClubPlanningDayPersonAndAssignmentTypeAsForeignKeys() {
        assertThat(mapper.foreignKeys())
                .containsExactly(
                        EntityType.CLUB,
                        EntityType.PLANNING_DAY,
                        EntityType.PERSON,
                        EntityType.PLANNING_DAY_ASSIGNMENT_TYPE);
    }
}
