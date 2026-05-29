package ch.alpenflight.migration.bundle.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PersonCategoryMapperTest extends AbstractMapperContractTest<PersonCategoryMapper> {

    private final PersonCategoryMapper mapper = new PersonCategoryMapper();

    @Override
    protected PersonCategoryMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("PersonCategoryId", randomUuidString(faker));
        row.put("ClubId", randomUuidString(faker));
        row.put("CategoryName", faker.options().option("Pilots", "Instructors", "Trainees"));
        row.put("Remarks", faker.lorem().sentence());
        row.put("ParentPersonCategoryId", randomUuidString(faker));
        row.put("CreatedOn", Timestamp.from(Instant.parse("2024-04-01T12:00:00Z")));
        row.put("CreatedByUserId", randomUuidString(faker));
        row.put("ModifiedOn", Timestamp.from(Instant.parse("2024-06-01T12:00:00Z")));
        row.put("ModifiedByUserId", randomUuidString(faker));
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-08-01T12:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    @Test
    void exposesPersonCategoryEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.PERSON_CATEGORY);
    }

    @Test
    void declaresClubAsTheOnlyStructuralForeignKey() {
        assertThat(mapper.foreignKeys()).containsExactly(EntityType.CLUB);
    }

    @Test
    void readEntityWithNullParentBindsSqlNull() throws Exception {
        ObjectNode emitted = new ObjectMapper().createObjectNode();
        emitted.put(PersonCategoryMapper.LEGACY_GUID, UUID.randomUUID().toString());
        emitted.put(PersonCategoryMapper.CLUB_ID, UUID.randomUUID().toString());
        emitted.put(PersonCategoryMapper.CATEGORY_NAME, "Root");
        emitted.putNull(PersonCategoryMapper.REMARKS);
        emitted.putNull(PersonCategoryMapper.PARENT_PERSON_CATEGORY_ID);
        emitted.put(PersonCategoryMapper.CREATED_ON, "2024-04-01T12:00:00Z");
        emitted.put(PersonCategoryMapper.CREATED_BY_USER_ID, UUID.randomUUID().toString());
        emitted.putNull(PersonCategoryMapper.MODIFIED_ON);
        emitted.putNull(PersonCategoryMapper.MODIFIED_BY_USER_ID);
        emitted.putNull(PersonCategoryMapper.DELETED_ON);
        emitted.putNull(PersonCategoryMapper.DELETED_BY_USER_ID);

        Map<Integer, Object> binds = new TreeMap<>();
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        org.mockito.stubbing.Answer<Void> capture = invocation -> {
            binds.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        };
        doAnswer(capture).when(ps).setObject(anyInt(), any());
        doAnswer(capture).when(ps).setString(anyInt(), any());
        doAnswer(capture).when(ps).setTimestamp(anyInt(), any());
        mapper.readEntity(emitted, ps);

        int parentPosition = positionOf(mapper, PersonCategoryMapper.PARENT_PERSON_CATEGORY_ID);
        assertThat(binds.get(parentPosition))
                .as("parent_person_category_id must bind SQL NULL to document the "
                        + "deferred self-FK two-pass at S-141")
                .isNull();
    }
}
