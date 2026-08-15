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

class PlanningDayAssignmentTypeMapperTest
        extends AbstractMapperContractTest<PlanningDayAssignmentTypeMapper> {

    private final PlanningDayAssignmentTypeMapper mapper = new PlanningDayAssignmentTypeMapper();

    @Override
    protected PlanningDayAssignmentTypeMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("PlanningDayAssignmentTypeId", randomUuidString(faker));
        row.put("ClubId", randomUuidString(faker));
        row.put("AssignmentTypeName", "WinchOperator");
        row.put("RequiredNrOfAssignments", 2);
        row.put("CreatedOn", Timestamp.from(Instant.parse("2024-01-01T12:00:00Z")));
        row.put("CreatedByUserId", randomUuidString(faker));
        row.put("ModifiedOn", Timestamp.from(Instant.parse("2024-02-01T12:00:00Z")));
        row.put("ModifiedByUserId", randomUuidString(faker));
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-03-01T12:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    @Test
    void exposesPlanningDayAssignmentTypeEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.PLANNING_DAY_ASSIGNMENT_TYPE);
    }

    @Test
    void declaresOnlyClubAsForeignKey() {
        assertThat(mapper.foreignKeyTargets()).containsExactly(EntityType.CLUB);
    }
}
