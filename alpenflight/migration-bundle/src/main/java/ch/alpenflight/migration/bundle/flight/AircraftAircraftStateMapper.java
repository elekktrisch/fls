package ch.alpenflight.migration.bundle.flight;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.ParityIgnore;
import ch.alpenflight.migration.bundle.ReferenceLookup;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public final class AircraftAircraftStateMapper implements Mapper {

    static final String AIRCRAFT_ID = "aircraft_id";
    static final String AIRCRAFT_STATE_ID = "aircraft_state_id";
    static final String VALID_FROM = "valid_from";
    static final String VALID_TO = "valid_to";
    static final String NOTICED_BY_PERSON_ID = "noticed_by_person_id";
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
            AIRCRAFT_ID, AIRCRAFT_STATE_ID, VALID_FROM, VALID_TO,
            NOTICED_BY_PERSON_ID, REMARKS,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.AIRCRAFT_AIRCRAFT_STATE;
    }

    @Override
    public String[] wireColumns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeyTargets() {
        return List.of(EntityType.AIRCRAFT);
    }

    @Override
    public List<ReferenceLookup> referenceLookups() {
        return List.of(
                new ReferenceLookup(AIRCRAFT_STATE_ID, "t_aircraft_state"));
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(AIRCRAFT_ID, source.getString("AircraftId"));
        target.writeStringField(AIRCRAFT_STATE_ID,
                Coercions.legacyIntIdToUuidString(source.getInt("AircraftState")));
        Coercions.writeRequiredTimestamp(target, VALID_FROM, source.getTimestamp("ValidFrom"));
        Coercions.writeOptionalTimestamp(target, VALID_TO, source.getTimestamp("ValidTo"));
        Coercions.writeOptionalString(target, NOTICED_BY_PERSON_ID,
                source.getString("NoticedByPersonId"));
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
        target.setObject(position++, UUID.fromString(source.get(AIRCRAFT_ID).asText()));
        target.setObject(position++, UUID.fromString(source.get(AIRCRAFT_STATE_ID).asText()));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, VALID_FROM));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, VALID_TO));
        target.setObject(position++, Coercions.readUuidOrNull(source, NOTICED_BY_PERSON_ID));
        target.setString(position++, Coercions.readStringOrNull(source, REMARKS));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
