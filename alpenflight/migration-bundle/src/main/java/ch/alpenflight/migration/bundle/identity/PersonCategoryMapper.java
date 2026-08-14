package ch.alpenflight.migration.bundle.identity;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public final class PersonCategoryMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";
    static final String CLUB_ID = "club_id";
    static final String CATEGORY_NAME = "category_name";
    static final String REMARKS = "remarks";
    static final String PARENT_PERSON_CATEGORY_ID = "parent_person_category_id";
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            LEGACY_GUID, CLUB_ID, CATEGORY_NAME, REMARKS, PARENT_PERSON_CATEGORY_ID,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.PERSON_CATEGORY;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        return List.of(EntityType.CLUB);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("PersonCategoryId"));
        target.writeStringField(CLUB_ID, source.getString("ClubId"));
        target.writeStringField(CATEGORY_NAME, source.getString("CategoryName"));
        Coercions.writeOptionalString(target, REMARKS, source.getString("Remarks"));
        Coercions.writeOptionalString(target, PARENT_PERSON_CATEGORY_ID,
                source.getString("ParentPersonCategoryId"));
        Coercions.writeRequiredTimestamp(target, CREATED_ON, source.getTimestamp("CreatedOn"));
        target.writeStringField(CREATED_BY_USER_ID, source.getString("CreatedByUserId"));
        Coercions.writeRequiredTimestampCoalescing(
                target, MODIFIED_ON, source.getTimestamp("ModifiedOn"),
                source.getTimestamp("CreatedOn"));
        Coercions.writeOptionalString(target, MODIFIED_BY_USER_ID,
                source.getString("ModifiedByUserId"));
        Coercions.writeOptionalTimestamp(target, DELETED_ON, source.getTimestamp("DeletedOn"));
        Coercions.writeOptionalString(target, DELETED_BY_USER_ID,
                source.getString("DeletedByUserId"));
        target.writeEndObject();
    }

    @Override
    public void readEntity(JsonNode source, PreparedStatement target) throws SQLException {
        target.setObject(1, UUID.fromString(source.get(LEGACY_GUID).asText()));
        target.setObject(2, UUID.fromString(source.get(CLUB_ID).asText()));
        target.setString(3, source.get(CATEGORY_NAME).asText());
        target.setString(4, Coercions.readStringOrNull(source, REMARKS));
        target.setObject(5, Coercions.readUuidOrNull(source, PARENT_PERSON_CATEGORY_ID));
        target.setTimestamp(6, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(7, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(8, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(9, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(10, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(11, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
