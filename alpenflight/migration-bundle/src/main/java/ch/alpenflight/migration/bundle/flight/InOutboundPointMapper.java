package ch.alpenflight.migration.bundle.flight;

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

public final class InOutboundPointMapper implements Mapper {

    static final String ID = "id";
    static final String LEGACY_GUID = "legacy_guid";
    static final String LOCATION_ID = "location_id";
    static final String CLUB_ID = "club_id";
    static final String POINT_NAME = "point_name";
    static final String DIRECTION = "direction";

    @ParityIgnore
    static final String POINT_TYPE = "point_type";
    @ParityIgnore
    static final String DESCRIPTION = "description";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            ID, LEGACY_GUID, LOCATION_ID, POINT_NAME, POINT_TYPE, DIRECTION, DESCRIPTION,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.INOUTBOUND_POINT;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        return List.of(EntityType.LOCATION);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        UUID legacyIopId = UUID.fromString(source.getString("InOutboundPointId"));
        UUID legacyClubId = UUID.fromString(source.getString("ClubId"));
        target.writeStringField(ID,
                Coercions.deriveFanOutId(legacyIopId, legacyClubId).toString());
        target.writeStringField(LEGACY_GUID, legacyIopId.toString());
        target.writeStringField(LOCATION_ID, source.getString("LocationId"));
        target.writeStringField(CLUB_ID, legacyClubId.toString());
        target.writeStringField(POINT_NAME, source.getString("InOutboundPointName"));
        target.writeNullField(POINT_TYPE);
        Coercions.writeOptionalString(target, DIRECTION,
                directionToken(source.getBoolean("IsInboundPoint"),
                        source.getBoolean("IsOutboundPoint")));
        target.writeNullField(DESCRIPTION);
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
        target.setObject(position++, UUID.fromString(source.get(ID).asText()));
        target.setObject(position++, UUID.fromString(source.get(LEGACY_GUID).asText()));
        target.setObject(position++, UUID.fromString(source.get(LOCATION_ID).asText()));
        target.setString(position++, source.get(POINT_NAME).asText());
        target.setString(position++, Coercions.readStringOrNull(source, POINT_TYPE));
        target.setString(position++, Coercions.readStringOrNull(source, DIRECTION));
        target.setString(position++, Coercions.readStringOrNull(source, DESCRIPTION));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }

    private static String directionToken(boolean inbound, boolean outbound) {
        if (inbound && outbound) {
            return "INOUTBOUND";
        }
        if (inbound) {
            return "INBOUND";
        }
        if (outbound) {
            return "OUTBOUND";
        }
        return null;
    }
}
