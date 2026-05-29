package ch.alpenflight.migration.bundle.identity;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class MemberStateMapperTest extends AbstractMapperContractTest<MemberStateMapper> {

    private final MemberStateMapper mapper = new MemberStateMapper();

    @Override
    protected MemberStateMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("MemberStateId", randomUuidString(faker));
        row.put("ClubId", randomUuidString(faker));
        row.put("MemberStateName", faker.options().option("Active", "Honorary", "Resigned"));
        row.put("Remarks", faker.lorem().sentence());
        row.put("CreatedOn", Timestamp.from(Instant.parse("2024-04-01T12:00:00Z")));
        row.put("CreatedByUserId", randomUuidString(faker));
        row.put("ModifiedOn", Timestamp.from(Instant.parse("2024-06-01T12:00:00Z")));
        row.put("ModifiedByUserId", randomUuidString(faker));
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-08-01T12:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    @Test
    void exposesMemberStateEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.MEMBER_STATE);
    }

    @Test
    void declaresClubAsTheOnlyStructuralForeignKey() {
        assertThat(mapper.foreignKeys()).containsExactly(EntityType.CLUB);
    }

    static String randomUuidString(Faker faker) {
        return new UUID(faker.random().nextLong(), faker.random().nextLong()).toString();
    }
}
