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
 * Tenant-scoped planning calendar aggregate root: legacy
 * {@code PlanningDays.PlanningDayId} → {@code t_planning_day.id}.
 * {@code operating_club_id} is the {@code @TenantId} discriminator per
 * V4.
 *
 * <p>Legacy column rename: {@code Day} → {@code planning_date} (the
 * column carries the planned-for date, not the row's creation date).
 *
 * <p>{@code location_id} → tenant-scoped {@link EntityType#LOCATION}.
 * Per S-185 the legacy {@code Locations} table fans out to one replica
 * per referencing Club, keyed via the composite
 * {@code legacy_id_map_location.(legacy_guid, club_id)} map. S-141
 * picks the replica matching this PlanningDay's
 * {@code operating_club_id}. No tenant bypass on PlanningDay itself —
 * the Location replica IS tenant-scoped.
 *
 * <p>{@code (operating_club_id, planning_date, location_id)} UNIQUE
 * partial: legacy had no equivalent constraint. Producer-side
 * dedupe-keep-first (deterministic on {@code (CreatedOn, PlanningDayId)})
 * + {@code PLANNING_DAY_DUPLICATE} warning. Mapper passes through; the
 * producer-side dedupe runs before any bundle row hits this mapper.
 *
 * <p>Legacy ASP.NET artifacts dropped: {@code OwnerId},
 * {@code OwnershipType}, {@code RecordState}, {@code IsDeleted}.
 * Legacy {@code Remarks} maps to the new {@code info} column.
 */
public final class PlanningDayMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";
    static final String OPERATING_CLUB_ID = "operating_club_id";
    static final String PLANNING_DATE = "planning_date";
    static final String LOCATION_ID = "location_id";
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
            LEGACY_GUID, OPERATING_CLUB_ID, PLANNING_DATE, LOCATION_ID, INFO,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.PLANNING_DAY;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        return List.of(EntityType.CLUB, EntityType.LOCATION);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("PlanningDayId"));
        target.writeStringField(OPERATING_CLUB_ID, source.getString("ClubId"));
        Coercions.writeOptionalDate(target, PLANNING_DATE, source.getDate("Day"));
        target.writeStringField(LOCATION_ID, source.getString("LocationId"));
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
        target.setDate(position++, Coercions.readDateOrNull(source, PLANNING_DATE));
        target.setObject(position++, UUID.fromString(source.get(LOCATION_ID).asText()));
        target.setString(position++, Coercions.readStringOrNull(source, INFO));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
