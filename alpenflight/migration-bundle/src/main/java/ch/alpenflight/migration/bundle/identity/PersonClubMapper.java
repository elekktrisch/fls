package ch.alpenflight.migration.bundle.identity;

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
import java.util.Set;
import java.util.UUID;

public final class PersonClubMapper implements Mapper {

    static final String PERSON_ID = "person_id";
    static final String CLUB_ID = "club_id";
    static final String MEMBER_NUMBER = "member_number";
    static final String MEMBER_STATE_ID = "member_state_id";
    static final String IS_MOTOR_PILOT = "is_motor_pilot";
    static final String IS_TOW_PILOT = "is_tow_pilot";
    static final String IS_GLIDER_INSTRUCTOR = "is_glider_instructor";
    static final String IS_GLIDER_PILOT = "is_glider_pilot";
    static final String IS_GLIDER_TRAINEE = "is_glider_trainee";
    static final String IS_PASSENGER = "is_passenger";
    static final String IS_WINCH_OPERATOR = "is_winch_operator";
    static final String IS_MOTOR_INSTRUCTOR = "is_motor_instructor";
    static final String RECEIVE_FLIGHT_REPORTS = "receive_flight_reports";
    static final String RECEIVE_AIRCRAFT_RESERVATION_NOTIFICATIONS =
            "receive_aircraft_reservation_notifications";
    static final String RECEIVE_PLANNING_DAY_ROLE_REMINDER = "receive_planning_day_role_reminder";
    static final String IS_ACTIVE = "is_active";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            PERSON_ID, CLUB_ID, MEMBER_NUMBER, MEMBER_STATE_ID,
            IS_MOTOR_PILOT, IS_TOW_PILOT, IS_GLIDER_INSTRUCTOR, IS_GLIDER_PILOT,
            IS_GLIDER_TRAINEE, IS_PASSENGER, IS_WINCH_OPERATOR, IS_MOTOR_INSTRUCTOR,
            RECEIVE_FLIGHT_REPORTS, RECEIVE_AIRCRAFT_RESERVATION_NOTIFICATIONS,
            RECEIVE_PLANNING_DAY_ROLE_REMINDER, IS_ACTIVE,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.PERSON_CLUB;
    }

    @Override
    public String[] wireColumns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeyTargets() {
        return List.of(EntityType.PERSON, EntityType.CLUB);
    }

    @Override
    public Set<String> crossTenantForeignKeyColumns() {
        return Set.of(PERSON_ID);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(PERSON_ID, source.getString("PersonId"));
        target.writeStringField(CLUB_ID, source.getString("ClubId"));
        Coercions.writeOptionalString(target, MEMBER_NUMBER, source.getString("MemberNumber"));
        Coercions.writeOptionalString(target, MEMBER_STATE_ID, source.getString("MemberStateId"));
        target.writeBooleanField(IS_MOTOR_PILOT, source.getBoolean("IsMotorPilot"));
        target.writeBooleanField(IS_TOW_PILOT, source.getBoolean("IsTowPilot"));
        target.writeBooleanField(IS_GLIDER_INSTRUCTOR, source.getBoolean("IsGliderInstructor"));
        target.writeBooleanField(IS_GLIDER_PILOT, source.getBoolean("IsGliderPilot"));
        target.writeBooleanField(IS_GLIDER_TRAINEE, source.getBoolean("IsGliderTrainee"));
        target.writeBooleanField(IS_PASSENGER, source.getBoolean("IsPassenger"));
        target.writeBooleanField(IS_WINCH_OPERATOR, source.getBoolean("IsWinchOperator"));
        target.writeBooleanField(IS_MOTOR_INSTRUCTOR, source.getBoolean("IsMotorInstructor"));
        target.writeBooleanField(RECEIVE_FLIGHT_REPORTS, source.getBoolean("ReceiveFlightReports"));
        target.writeBooleanField(RECEIVE_AIRCRAFT_RESERVATION_NOTIFICATIONS,
                source.getBoolean("ReceiveAircraftReservationNotifications"));
        target.writeBooleanField(RECEIVE_PLANNING_DAY_ROLE_REMINDER,
                source.getBoolean("ReceivePlanningDayRoleReminder"));
        target.writeBooleanField(IS_ACTIVE, source.getBoolean("IsActive"));
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
        target.setObject(position++, UUID.fromString(source.get(PERSON_ID).asText()));
        target.setObject(position++, UUID.fromString(source.get(CLUB_ID).asText()));
        target.setString(position++, Coercions.readStringOrNull(source, MEMBER_NUMBER));
        target.setObject(position++, Coercions.readUuidOrNull(source, MEMBER_STATE_ID));
        target.setObject(position++, source.get(IS_MOTOR_PILOT).asBoolean());
        target.setObject(position++, source.get(IS_TOW_PILOT).asBoolean());
        target.setObject(position++, source.get(IS_GLIDER_INSTRUCTOR).asBoolean());
        target.setObject(position++, source.get(IS_GLIDER_PILOT).asBoolean());
        target.setObject(position++, source.get(IS_GLIDER_TRAINEE).asBoolean());
        target.setObject(position++, source.get(IS_PASSENGER).asBoolean());
        target.setObject(position++, source.get(IS_WINCH_OPERATOR).asBoolean());
        target.setObject(position++, source.get(IS_MOTOR_INSTRUCTOR).asBoolean());
        target.setObject(position++, source.get(RECEIVE_FLIGHT_REPORTS).asBoolean());
        target.setObject(position++,
                source.get(RECEIVE_AIRCRAFT_RESERVATION_NOTIFICATIONS).asBoolean());
        target.setObject(position++, source.get(RECEIVE_PLANNING_DAY_ROLE_REMINDER).asBoolean());
        target.setObject(position++, source.get(IS_ACTIVE).asBoolean());
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
