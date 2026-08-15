package ch.alpenflight.migration.bundle.accounting;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.ForeignKeyColumn;
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

public final class AircraftReservationTypeMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";
    static final String OPERATING_CLUB_ID = "operating_club_id";
    static final String RESERVATION_TYPE_NAME = "reservation_type_name";
    static final String IS_INSTRUCTOR_REQUIRED = "is_instructor_required";
    static final String IS_MAINTENANCE = "is_maintenance";
    static final String IS_ACTIVE = "is_active";
    static final String REMARKS = "remarks";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            LEGACY_GUID, OPERATING_CLUB_ID, RESERVATION_TYPE_NAME,
            IS_INSTRUCTOR_REQUIRED, IS_MAINTENANCE, IS_ACTIVE, REMARKS,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.AIRCRAFT_RESERVATION_TYPE;
    }

    @Override
    public String[] wireColumns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeyTargets() {
        return List.of(EntityType.CLUB);
    }

    @Override
    public List<ForeignKeyColumn> foreignKeyColumns() {
        return List.of(new ForeignKeyColumn(OPERATING_CLUB_ID, EntityType.CLUB));
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("AircraftReservationTypeId"));
        target.writeStringField(OPERATING_CLUB_ID, source.getString("ClubId"));
        target.writeStringField(RESERVATION_TYPE_NAME,
                source.getString("AircraftReservationTypeName"));
        target.writeBooleanField(IS_INSTRUCTOR_REQUIRED,
                source.getBoolean("IsInstructorRequired"));
        target.writeBooleanField(IS_MAINTENANCE, source.getBoolean("IsMaintenance"));
        target.writeBooleanField(IS_ACTIVE, source.getBoolean("IsActive"));
        Coercions.writeOptionalString(target, REMARKS, source.getString("Remarks"));
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
        int position = 1;
        target.setObject(position++, UUID.fromString(source.get(LEGACY_GUID).asText()));
        target.setObject(position++, UUID.fromString(source.get(OPERATING_CLUB_ID).asText()));
        target.setString(position++, source.get(RESERVATION_TYPE_NAME).asText());
        target.setObject(position++, source.get(IS_INSTRUCTOR_REQUIRED).asBoolean());
        target.setObject(position++, source.get(IS_MAINTENANCE).asBoolean());
        target.setObject(position++, source.get(IS_ACTIVE).asBoolean());
        target.setString(position++, Coercions.readStringOrNull(source, REMARKS));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
