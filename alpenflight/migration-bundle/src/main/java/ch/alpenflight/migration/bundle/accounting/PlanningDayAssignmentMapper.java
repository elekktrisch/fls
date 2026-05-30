package ch.alpenflight.migration.bundle.accounting;

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
 * Tenant-scoped planning role assignment: legacy
 * {@code PlanningDayAssignments.PlanningDayAssignmentId} →
 * {@code t_planning_day_assignment.id}. {@code operating_club_id}
 * (denormalised from the linked {@code planning_day} per V4) is the
 * {@code @TenantId} discriminator.
 *
 * <p>{@code assigned_person_id} → cross-tenant {@link EntityType#PERSON}
 * (FK RESTRICT per V4). Declared in
 * {@link ch.alpenflight.migration.bundle.Manifest}'s
 * {@code TENANT_BYPASS_ALLOW_LIST} via the S-186 widening — the
 * per-bundle Person sub-map handles the rewrite at S-141 ingest.
 *
 * <p>Legacy ASP.NET artifacts dropped: {@code OwnerId},
 * {@code OwnershipType}, {@code RecordState}, {@code IsDeleted}.
 * Legacy {@code Remarks} maps to the new {@code info} column.
 */
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
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        return List.of(
                EntityType.CLUB,
                EntityType.PLANNING_DAY,
                EntityType.PERSON,
                EntityType.PLANNING_DAY_ASSIGNMENT_TYPE);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("PlanningDayAssignmentId"));
        // OperatingClubId denormalised producer-side via JOIN to PlanningDays
        // per V4 schema's planning_day_assignment.operating_club_id rule.
        target.writeStringField(OPERATING_CLUB_ID, source.getString("OperatingClubId"));
        target.writeStringField(PLANNING_DAY_ID, source.getString("AssignedPlanningDayId"));
        target.writeStringField(ASSIGNED_PERSON_ID, source.getString("AssignedPersonId"));
        target.writeStringField(ASSIGNMENT_TYPE_ID, source.getString("AssignmentTypeId"));
        Coercions.writeOptionalString(target, INFO, source.getString("Remarks"));
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
