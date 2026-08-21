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
import java.util.Set;
import java.util.UUID;

public final class PlanningDayAssignmentMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";
    static final String OPERATING_CLUB_ID = "operating_club_id";
    static final String PLANNING_DAY_ID = "planning_day_id";
    static final String ASSIGNED_PERSON_ID = "assigned_person_id";
    static final String ASSIGNMENT_TYPE_ID = "assignment_type_id";
    static final String INFO = "info";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            LEGACY_GUID, OPERATING_CLUB_ID, PLANNING_DAY_ID,
            ASSIGNED_PERSON_ID, ASSIGNMENT_TYPE_ID, INFO,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.PLANNING_DAY_ASSIGNMENT;
    }

    @Override
    public String[] wireColumns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeyTargets() {
        return List.of(
                EntityType.CLUB,
                EntityType.PLANNING_DAY,
                EntityType.PERSON,
                EntityType.PLANNING_DAY_ASSIGNMENT_TYPE);
    }

    @Override
    public List<ForeignKeyColumn> foreignKeyColumns() {
        return List.of(
                new ForeignKeyColumn(OPERATING_CLUB_ID, EntityType.CLUB),
                new ForeignKeyColumn(ASSIGNED_PERSON_ID, EntityType.PERSON),
                new ForeignKeyColumn(
                        ASSIGNMENT_TYPE_ID, EntityType.PLANNING_DAY_ASSIGNMENT_TYPE));
    }

    @Override
    public Set<String> crossTenantForeignKeyColumns() {
        return Set.of(ASSIGNED_PERSON_ID);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("PlanningDayAssignmentId"));
        target.writeStringField(OPERATING_CLUB_ID, source.getString("OperatingClubId"));
        target.writeStringField(PLANNING_DAY_ID, source.getString("AssignedPlanningDayId"));
        target.writeStringField(ASSIGNED_PERSON_ID, source.getString("AssignedPersonId"));
        target.writeStringField(ASSIGNMENT_TYPE_ID, source.getString("AssignmentTypeId"));
        Coercions.writeOptionalString(target, INFO, source.getString("Remarks"));
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
        target.setObject(position++, UUID.fromString(source.get(PLANNING_DAY_ID).asText()));
        target.setObject(position++, UUID.fromString(source.get(ASSIGNED_PERSON_ID).asText()));
        target.setObject(position++, UUID.fromString(source.get(ASSIGNMENT_TYPE_ID).asText()));
        target.setString(position++, Coercions.readStringOrNull(source, INFO));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
