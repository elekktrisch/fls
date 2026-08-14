package ch.alpenflight.migration.bundle.flight;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.ForeignKeyColumn;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.ParityIgnore;
import ch.alpenflight.migration.bundle.ParitySentinel;
import ch.alpenflight.migration.bundle.ReferenceLookup;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

public final class FlightMapper implements Mapper {

    static final int LEGACY_AIR_STATE_FLIGHT_PLAN_OPEN = 5;

    private static final String LEGACY_FLIGHTS_OPERATING_CLUB_COLUMN = "OwnerId";

    static final String LEGACY_GUID = "legacy_guid";
    static final String OPERATING_CLUB_ID = "operating_club_id";
    static final String AIRCRAFT_ID = "aircraft_id";
    static final String FLIGHT_DATE = "flight_date";
    static final String START_DATE_TIME = "start_date_time";
    static final String LDG_DATE_TIME = "ldg_date_time";
    static final String BLOCK_START_DATE_TIME = "block_start_date_time";
    static final String BLOCK_END_DATE_TIME = "block_end_date_time";
    static final String START_LOCATION_ID = "start_location_id";
    static final String LDG_LOCATION_ID = "ldg_location_id";
    static final String START_RUNWAY = "start_runway";
    static final String LDG_RUNWAY = "ldg_runway";
    static final String OUTBOUND_ROUTE = "outbound_route";
    static final String INBOUND_ROUTE = "inbound_route";
    static final String FLIGHT_TYPE_ID = "flight_type_id";
    static final String IS_SOLO_FLIGHT = "is_solo_flight";
    static final String START_TYPE_ID = "start_type_id";
    static final String TOW_FLIGHT_ID = "tow_flight_id";
    static final String NR_OF_LDGS = "nr_of_ldgs";
    static final String NR_OF_LDGS_ON_START_LOCATION = "nr_of_ldgs_on_start_location";
    static final String NO_START_TIME_INFORMATION = "no_start_time_information";
    static final String NO_LDG_TIME_INFORMATION = "no_ldg_time_information";

    @ParitySentinel
    static final String FLIGHT_PLAN_OPENED_ON = "flight_plan_opened_on";

    static final String PROCESS_STATE_ID = "process_state_id";
    static final String FLIGHT_AIRCRAFT_TYPE_ID = "flight_aircraft_type_id";
    static final String ENGINE_START_OPERATING_COUNTER_IN_SECONDS =
            "engine_start_operating_counter_in_seconds";
    static final String ENGINE_END_OPERATING_COUNTER_IN_SECONDS =
            "engine_end_operating_counter_in_seconds";
    static final String COMMENT = "comment";
    static final String INCIDENT_COMMENT = "incident_comment";
    static final String VALIDATION_ERRORS = "validation_errors";
    static final String COUPON_NUMBER = "coupon_number";
    static final String FLIGHT_COST_BALANCE_TYPE_ID = "flight_cost_balance_type_id";
    static final String DELIVERY_CREATED_ON = "delivery_created_on";
    static final String VALIDATED_ON = "validated_on";
    static final String NR_OF_PASSENGERS = "nr_of_passengers";
    static final String START_POSITION = "start_position";
    static final String FLIGHT_REPORT_SENT_ON = "flight_report_sent_on";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    @ParityIgnore
    static final String LOCKED_AT = "locked_at";

    static final int LEGACY_PROCESS_STATE_LOCKED = 40;

    private static final String[] COLUMNS = {
            LEGACY_GUID, OPERATING_CLUB_ID, AIRCRAFT_ID,
            FLIGHT_DATE, START_DATE_TIME, LDG_DATE_TIME,
            BLOCK_START_DATE_TIME, BLOCK_END_DATE_TIME,
            START_LOCATION_ID, LDG_LOCATION_ID,
            START_RUNWAY, LDG_RUNWAY, OUTBOUND_ROUTE, INBOUND_ROUTE,
            FLIGHT_TYPE_ID, IS_SOLO_FLIGHT,
            START_TYPE_ID, TOW_FLIGHT_ID,
            NR_OF_LDGS, NR_OF_LDGS_ON_START_LOCATION,
            NO_START_TIME_INFORMATION, NO_LDG_TIME_INFORMATION,
            FLIGHT_PLAN_OPENED_ON,
            PROCESS_STATE_ID, FLIGHT_AIRCRAFT_TYPE_ID,
            ENGINE_START_OPERATING_COUNTER_IN_SECONDS,
            ENGINE_END_OPERATING_COUNTER_IN_SECONDS,
            COMMENT, INCIDENT_COMMENT, VALIDATION_ERRORS,
            COUPON_NUMBER, FLIGHT_COST_BALANCE_TYPE_ID,
            DELIVERY_CREATED_ON, VALIDATED_ON,
            NR_OF_PASSENGERS, START_POSITION, FLIGHT_REPORT_SENT_ON,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID,
            LOCKED_AT
    };

    @Override
    public EntityType entityType() {
        return EntityType.FLIGHT;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        return List.of(
                EntityType.CLUB, EntityType.AIRCRAFT, EntityType.LOCATION,
                EntityType.FLIGHT_TYPE, EntityType.START_TYPE);
    }

    @Override
    public List<ForeignKeyColumn> foreignKeyColumns() {
        return List.of(
                new ForeignKeyColumn(OPERATING_CLUB_ID, EntityType.CLUB),
                new ForeignKeyColumn(START_LOCATION_ID, EntityType.LOCATION, OPERATING_CLUB_ID),
                new ForeignKeyColumn(LDG_LOCATION_ID, EntityType.LOCATION, OPERATING_CLUB_ID));
    }

    @Override
    public List<String> deferredSelfFkColumns() {
        return List.of(TOW_FLIGHT_ID);
    }

    @Override
    public List<ReferenceLookup> referenceLookups() {
        return List.of(
                new ReferenceLookup(PROCESS_STATE_ID, "t_flight_process_state"),
                new ReferenceLookup(FLIGHT_COST_BALANCE_TYPE_ID, "t_flight_cost_balance_type"));
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("FlightId"));
        target.writeStringField(OPERATING_CLUB_ID,
                source.getString(LEGACY_FLIGHTS_OPERATING_CLUB_COLUMN));
        target.writeStringField(AIRCRAFT_ID, source.getString("AircraftId"));
        Coercions.writeOptionalDate(target, FLIGHT_DATE, source.getDate("FlightDate"));
        Coercions.writeOptionalTimestamp(target, START_DATE_TIME,
                source.getTimestamp("StartDateTime"));
        Coercions.writeOptionalTimestamp(target, LDG_DATE_TIME,
                source.getTimestamp("LdgDateTime"));
        Coercions.writeOptionalTimestamp(target, BLOCK_START_DATE_TIME,
                source.getTimestamp("BlockStartDateTime"));
        Coercions.writeOptionalTimestamp(target, BLOCK_END_DATE_TIME,
                source.getTimestamp("BlockEndDateTime"));
        Coercions.writeOptionalGuidString(target, START_LOCATION_ID,
                source.getString("StartLocationId"));
        Coercions.writeOptionalGuidString(target, LDG_LOCATION_ID,
                source.getString("LdgLocationId"));
        Coercions.writeOptionalString(target, START_RUNWAY, source.getString("StartRunway"));
        Coercions.writeOptionalString(target, LDG_RUNWAY, source.getString("LdgRunway"));
        Coercions.writeOptionalString(target, OUTBOUND_ROUTE, source.getString("OutboundRoute"));
        Coercions.writeOptionalString(target, INBOUND_ROUTE, source.getString("InboundRoute"));
        Coercions.writeOptionalGuidString(target, FLIGHT_TYPE_ID,
                source.getString("FlightTypeId"));
        target.writeBooleanField(IS_SOLO_FLIGHT, source.getBoolean("IsSoloFlight"));
        Coercions.writeOptionalString(target, START_TYPE_ID,
                Coercions.optionalLegacyIntIdAsUuidString(source, "StartType"));
        Coercions.writeOptionalGuidString(target, TOW_FLIGHT_ID,
                source.getString("TowFlightId"));
        Coercions.writeOptionalShort(target, NR_OF_LDGS,
                source.getObject("NrOfLdgs", Short.class));
        Coercions.writeOptionalShort(target, NR_OF_LDGS_ON_START_LOCATION,
                source.getObject("NrOfLdgsOnStartLocation", Short.class));
        target.writeBooleanField(NO_START_TIME_INFORMATION,
                source.getBoolean("NoStartTimeInformation"));
        target.writeBooleanField(NO_LDG_TIME_INFORMATION,
                source.getBoolean("NoLdgTimeInformation"));
        int currentLegacyAirState = source.getInt("AirStateId");
        Timestamp flightPlanOpenedOn = currentLegacyAirState == LEGACY_AIR_STATE_FLIGHT_PLAN_OPEN
                ? source.getTimestamp("ModifiedOn")
                : null;
        Coercions.writeOptionalTimestamp(target, FLIGHT_PLAN_OPENED_ON, flightPlanOpenedOn);
        target.writeStringField(PROCESS_STATE_ID,
                Coercions.legacyIntIdToUuidString(source.getInt("ProcessStateId")));
        target.writeNumberField(FLIGHT_AIRCRAFT_TYPE_ID,
                (short) source.getInt("FlightAircraftType"));
        Coercions.writeOptionalLong(target, ENGINE_START_OPERATING_COUNTER_IN_SECONDS,
                source.getObject("EngineStartOperatingCounterInSeconds", Long.class));
        Coercions.writeOptionalLong(target, ENGINE_END_OPERATING_COUNTER_IN_SECONDS,
                source.getObject("EngineEndOperatingCounterInSeconds", Long.class));
        Coercions.writeOptionalString(target, COMMENT, source.getString("Comment"));
        Coercions.writeOptionalString(target, INCIDENT_COMMENT,
                source.getString("IncidentComment"));
        Coercions.writeOptionalString(target, VALIDATION_ERRORS,
                source.getString("ValidationErrors"));
        Coercions.writeOptionalString(target, COUPON_NUMBER, source.getString("CouponNumber"));
        Coercions.writeOptionalString(target, FLIGHT_COST_BALANCE_TYPE_ID,
                Coercions.optionalLegacyIntIdAsUuidString(source, "FlightCostBalanceType"));
        Coercions.writeOptionalTimestamp(target, DELIVERY_CREATED_ON,
                source.getTimestamp("DeliveryCreatedOn"));
        Coercions.writeOptionalTimestamp(target, VALIDATED_ON,
                source.getTimestamp("ValidatedOn"));
        Coercions.writeOptionalShort(target, NR_OF_PASSENGERS,
                source.getObject("NrOfPassengers", Short.class));
        Coercions.writeOptionalShort(target, START_POSITION,
                source.getObject("StartPosition", Short.class));
        Coercions.writeOptionalTimestamp(target, FLIGHT_REPORT_SENT_ON,
                source.getTimestamp("FlightReportSentOn"));
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
        Timestamp legacyLockTimeProxy =
                source.getInt("ProcessStateId") >= LEGACY_PROCESS_STATE_LOCKED
                        ? source.getTimestamp("ModifiedOn")
                        : null;
        Coercions.writeOptionalTimestamp(target, LOCKED_AT, legacyLockTimeProxy);
        target.writeEndObject();
    }

    @Override
    public void readEntity(JsonNode source, PreparedStatement target) throws SQLException {
        int position = 1;
        target.setObject(position++, UUID.fromString(source.get(LEGACY_GUID).asText()));
        target.setObject(position++, UUID.fromString(source.get(OPERATING_CLUB_ID).asText()));
        target.setObject(position++, UUID.fromString(source.get(AIRCRAFT_ID).asText()));
        target.setDate(position++, Coercions.readDateOrNull(source, FLIGHT_DATE));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, START_DATE_TIME));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, LDG_DATE_TIME));
        target.setTimestamp(position++,
                Coercions.readTimestampOrNull(source, BLOCK_START_DATE_TIME));
        target.setTimestamp(position++,
                Coercions.readTimestampOrNull(source, BLOCK_END_DATE_TIME));
        target.setObject(position++, Coercions.readUuidOrNull(source, START_LOCATION_ID));
        target.setObject(position++, Coercions.readUuidOrNull(source, LDG_LOCATION_ID));
        target.setString(position++, Coercions.readStringOrNull(source, START_RUNWAY));
        target.setString(position++, Coercions.readStringOrNull(source, LDG_RUNWAY));
        target.setString(position++, Coercions.readStringOrNull(source, OUTBOUND_ROUTE));
        target.setString(position++, Coercions.readStringOrNull(source, INBOUND_ROUTE));
        target.setObject(position++, Coercions.readUuidOrNull(source, FLIGHT_TYPE_ID));
        target.setObject(position++, source.get(IS_SOLO_FLIGHT).asBoolean());
        target.setObject(position++, Coercions.readUuidOrNull(source, START_TYPE_ID));
        target.setObject(position++, Coercions.readUuidOrNull(source, TOW_FLIGHT_ID));
        Coercions.bindShortOrNull(target, position++, source, NR_OF_LDGS);
        Coercions.bindShortOrNull(target, position++, source, NR_OF_LDGS_ON_START_LOCATION);
        target.setObject(position++, source.get(NO_START_TIME_INFORMATION).asBoolean());
        target.setObject(position++, source.get(NO_LDG_TIME_INFORMATION).asBoolean());
        target.setTimestamp(position++,
                Coercions.readTimestampOrNull(source, FLIGHT_PLAN_OPENED_ON));
        target.setObject(position++, UUID.fromString(source.get(PROCESS_STATE_ID).asText()));
        target.setShort(position++, (short) source.get(FLIGHT_AIRCRAFT_TYPE_ID).intValue());
        target.setObject(position++,
                Coercions.readLongOrNull(source, ENGINE_START_OPERATING_COUNTER_IN_SECONDS));
        target.setObject(position++,
                Coercions.readLongOrNull(source, ENGINE_END_OPERATING_COUNTER_IN_SECONDS));
        target.setString(position++, Coercions.readStringOrNull(source, COMMENT));
        target.setString(position++, Coercions.readStringOrNull(source, INCIDENT_COMMENT));
        target.setString(position++, Coercions.readStringOrNull(source, VALIDATION_ERRORS));
        target.setString(position++, Coercions.readStringOrNull(source, COUPON_NUMBER));
        target.setObject(position++,
                Coercions.readUuidOrNull(source, FLIGHT_COST_BALANCE_TYPE_ID));
        target.setTimestamp(position++,
                Coercions.readTimestampOrNull(source, DELIVERY_CREATED_ON));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, VALIDATED_ON));
        Coercions.bindShortOrNull(target, position++, source, NR_OF_PASSENGERS);
        Coercions.bindShortOrNull(target, position++, source, START_POSITION);
        target.setTimestamp(position++,
                Coercions.readTimestampOrNull(source, FLIGHT_REPORT_SENT_ON));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
        target.setTimestamp(position, Coercions.readTimestampOrNull(source, LOCKED_AT));
    }
}
