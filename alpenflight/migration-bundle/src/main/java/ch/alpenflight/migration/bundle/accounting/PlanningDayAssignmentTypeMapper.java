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

/**
 * Tenant-scoped per-club reference: legacy
 * {@code PlanningDayAssignmentTypes.PlanningDayAssignmentTypeId} →
 * {@code t_planning_day_assignment_type.id}. {@code operating_club_id}
 * is the {@code @TenantId} discriminator per V4.
 *
 * <p>Legacy ASP.NET artifacts dropped: {@code OwnerId},
 * {@code OwnershipType}, {@code RecordState}, {@code IsDeleted}.
 */
public final class PlanningDayAssignmentTypeMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";
    static final String OPERATING_CLUB_ID = "operating_club_id";
    static final String ASSIGNMENT_TYPE_NAME = "assignment_type_name";
    static final String REQUIRED_NR_OF_ASSIGNMENTS = "required_nr_of_assignments";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            LEGACY_GUID, OPERATING_CLUB_ID, ASSIGNMENT_TYPE_NAME,
            REQUIRED_NR_OF_ASSIGNMENTS,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.PLANNING_DAY_ASSIGNMENT_TYPE;
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
    public List<ForeignKeyColumn> foreignKeyColumns() {
        // operating_club_id is the @TenantId (off the convention club_id), J-6 T-11.
        return List.of(new ForeignKeyColumn(OPERATING_CLUB_ID, EntityType.CLUB));
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("PlanningDayAssignmentTypeId"));
        target.writeStringField(OPERATING_CLUB_ID, source.getString("ClubId"));
        target.writeStringField(ASSIGNMENT_TYPE_NAME,
                source.getString("AssignmentTypeName"));
        target.writeNumberField(REQUIRED_NR_OF_ASSIGNMENTS,
                (short) source.getInt("RequiredNrOfAssignments"));
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
        target.setString(position++, source.get(ASSIGNMENT_TYPE_NAME).asText());
        target.setShort(position++, (short) source.get(REQUIRED_NR_OF_ASSIGNMENTS).intValue());
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
