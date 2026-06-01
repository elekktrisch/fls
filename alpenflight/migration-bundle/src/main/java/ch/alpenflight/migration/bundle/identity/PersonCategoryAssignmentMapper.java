package ch.alpenflight.migration.bundle.identity;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.ParityIgnore;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Legacy {@code PersonPersonCategories} composite junction
 * {@code (PersonId, PersonCategoryId)} → V17
 * {@code t_person_category_assignment} surrogate-keyed junction with a
 * denormalised {@code club_id} for @TenantId routing.
 *
 * <p>The producer-side {@code SELECT} joins {@code PersonCategories} on
 * the legacy side to pull {@code ClubId} into the projected row; the
 * legacy junction itself carries no club discriminator. S-141 mints the
 * surrogate {@code id UUID v7} at INSERT time.
 *
 * <p>Leaf junction — no inbound FKs from other identity-group entities
 * — so S-141 does NOT populate a
 * {@code legacy_id_map_person_category_assignment} temp table.
 */
public final class PersonCategoryAssignmentMapper implements Mapper {

    static final String PERSON_ID = "person_id";
    static final String PERSON_CATEGORY_ID = "person_category_id";
    static final String CLUB_ID = "club_id";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            PERSON_ID, PERSON_CATEGORY_ID, CLUB_ID,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.PERSON_CATEGORY_ASSIGNMENT;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        return List.of(EntityType.PERSON, EntityType.PERSON_CATEGORY, EntityType.CLUB);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(PERSON_ID, source.getString("PersonId"));
        target.writeStringField(PERSON_CATEGORY_ID, source.getString("PersonCategoryId"));
        target.writeStringField(CLUB_ID, source.getString("ClubId"));
        Coercions.writeRequiredTimestamp(target, CREATED_ON, source.getTimestamp("CreatedOn"));
        target.writeStringField(CREATED_BY_USER_ID, source.getString("CreatedByUserId"));
        Coercions.writeOptionalTimestamp(target, MODIFIED_ON, source.getTimestamp("ModifiedOn"));
        Coercions.writeOptionalString(target, MODIFIED_BY_USER_ID,
                source.getString("ModifiedByUserId"));
        Coercions.writeOptionalTimestamp(target, DELETED_ON, source.getTimestamp("DeletedOn"));
        Coercions.writeOptionalString(target, DELETED_BY_USER_ID,
                source.getString("DeletedByUserId"));
        target.writeEndObject();
    }

    @Override
    public void readEntity(JsonNode source, PreparedStatement target) throws SQLException {
        int position = 1;
        target.setObject(position++, UUID.fromString(source.get(PERSON_ID).asText()));
        target.setObject(position++, UUID.fromString(source.get(PERSON_CATEGORY_ID).asText()));
        target.setObject(position++, UUID.fromString(source.get(CLUB_ID).asText()));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
