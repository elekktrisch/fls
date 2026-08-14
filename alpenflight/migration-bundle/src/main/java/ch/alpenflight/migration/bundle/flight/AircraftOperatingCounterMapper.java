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

public final class AircraftOperatingCounterMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";
    static final String AIRCRAFT_ID = "aircraft_id";
    static final String AT_DATE_TIME = "at_date_time";
    static final String TOTAL_TOWED_GLIDER_STARTS = "total_towed_glider_starts";
    static final String TOTAL_WINCH_LAUNCH_STARTS = "total_winch_launch_starts";
    static final String TOTAL_SELF_STARTS = "total_self_starts";
    static final String FLIGHT_OPERATING_COUNTER_IN_SECONDS =
            "flight_operating_counter_in_seconds";
    static final String ENGINE_OPERATING_COUNTER_IN_SECONDS =
            "engine_operating_counter_in_seconds";
    static final String NEXT_MAINTENANCE_AT_FLIGHT_OPERATING_COUNTER_IN_SECONDS =
            "next_maintenance_at_flight_operating_counter_in_seconds";
    static final String NEXT_MAINTENANCE_AT_ENGINE_OPERATING_COUNTER_IN_SECONDS =
            "next_maintenance_at_engine_operating_counter_in_seconds";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            LEGACY_GUID, AIRCRAFT_ID, AT_DATE_TIME,
            TOTAL_TOWED_GLIDER_STARTS, TOTAL_WINCH_LAUNCH_STARTS, TOTAL_SELF_STARTS,
            FLIGHT_OPERATING_COUNTER_IN_SECONDS, ENGINE_OPERATING_COUNTER_IN_SECONDS,
            NEXT_MAINTENANCE_AT_FLIGHT_OPERATING_COUNTER_IN_SECONDS,
            NEXT_MAINTENANCE_AT_ENGINE_OPERATING_COUNTER_IN_SECONDS,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.AIRCRAFT_OPERATING_COUNTER;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        return List.of(EntityType.AIRCRAFT);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("AircraftOperatingCounterId"));
        target.writeStringField(AIRCRAFT_ID, source.getString("AircraftId"));
        Coercions.writeRequiredTimestamp(target, AT_DATE_TIME,
                source.getTimestamp("AtDateTime"));
        Coercions.writeOptionalInt(target, TOTAL_TOWED_GLIDER_STARTS,
                source.getObject("TotalTowedGliderStarts", Integer.class));
        Coercions.writeOptionalInt(target, TOTAL_WINCH_LAUNCH_STARTS,
                source.getObject("TotalWinchLaunchStarts", Integer.class));
        Coercions.writeOptionalInt(target, TOTAL_SELF_STARTS,
                source.getObject("TotalSelfStarts", Integer.class));
        Coercions.writeOptionalLong(target, FLIGHT_OPERATING_COUNTER_IN_SECONDS,
                source.getObject("FlightOperatingCounterInSeconds", Long.class));
        Coercions.writeOptionalLong(target, ENGINE_OPERATING_COUNTER_IN_SECONDS,
                source.getObject("EngineOperatingCounterInSeconds", Long.class));
        Coercions.writeOptionalLong(target, NEXT_MAINTENANCE_AT_FLIGHT_OPERATING_COUNTER_IN_SECONDS,
                source.getObject("NextMaintenanceAtFlightOperatingCounterInSeconds", Long.class));
        Coercions.writeOptionalLong(target, NEXT_MAINTENANCE_AT_ENGINE_OPERATING_COUNTER_IN_SECONDS,
                source.getObject("NextMaintenanceAtEngineOperatingCounterInSeconds", Long.class));
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
        target.setObject(position++, UUID.fromString(source.get(AIRCRAFT_ID).asText()));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, AT_DATE_TIME));
        target.setObject(position++, Coercions.readIntOrNull(source, TOTAL_TOWED_GLIDER_STARTS));
        target.setObject(position++, Coercions.readIntOrNull(source, TOTAL_WINCH_LAUNCH_STARTS));
        target.setObject(position++, Coercions.readIntOrNull(source, TOTAL_SELF_STARTS));
        target.setObject(position++,
                Coercions.readLongOrNull(source, FLIGHT_OPERATING_COUNTER_IN_SECONDS));
        target.setObject(position++,
                Coercions.readLongOrNull(source, ENGINE_OPERATING_COUNTER_IN_SECONDS));
        target.setObject(position++, Coercions.readLongOrNull(
                source, NEXT_MAINTENANCE_AT_FLIGHT_OPERATING_COUNTER_IN_SECONDS));
        target.setObject(position++, Coercions.readLongOrNull(
                source, NEXT_MAINTENANCE_AT_ENGINE_OPERATING_COUNTER_IN_SECONDS));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
