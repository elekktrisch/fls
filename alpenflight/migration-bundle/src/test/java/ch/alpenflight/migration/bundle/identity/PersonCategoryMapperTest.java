package ch.alpenflight.migration.bundle.identity;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        mapper.readEntity(emitted, ps);

        ArgumentCaptor<Object> values = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(ps, Mockito.atLeastOnce()).setObject(Mockito.anyInt(), values.capture());
        assertThat(values.getAllValues().get(4))
                .as("parent_person_category_id at position 5 must bind SQL NULL "
                        + "to document the deferred self-FK two-pass at S-141")
                .isNull();
    }

    private static String randomUuidString(Faker faker) {
        return new UUID(faker.random().nextLong(), faker.random().nextLong()).toString();
    }
}
