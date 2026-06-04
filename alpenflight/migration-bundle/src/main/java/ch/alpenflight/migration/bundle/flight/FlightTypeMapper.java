package ch.alpenflight.migration.bundle.flight;

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
 * Tenant-scoped per-club reference: legacy {@code FlightTypes.FlightTypeId}
 * → {@code t_flight_type.id}. {@code operating_club_id} is the
 * {@code @TenantId} discriminator per V3.
 *
 * <p>Seed-vs-bundle reconciliation: S-138's
 * {@code ReferenceDataSeeder} pre-creates default flight types per Club
 * at trial-deployment provisioning ({@code ON CONFLICT
 * (operating_club_id, flight_type_name) DO NOTHING}). S-141 runs
 * provisioning first, then bundle ingest — for name collisions the
 * seeded UUID wins and the legacy {@code Flight.FlightTypeId} FK resolves
 * through the natural-key lookup. No mapper-side change required.
 *
 * <p>Legacy ASP.NET artifacts dropped: {@code OwnerId},
 * {@code OwnershipType}, {@code RecordState}, {@code IsDeleted},
 * {@code IsSummarizedSystemFlight} (no destination column — system-flight
 * concept did not survive the rewrite).
 */
public final class FlightTypeMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";
    static final String OPERATING_CLUB_ID = "operating_club_id";
    static final String FLIGHT_TYPE_NAME = "flight_type_name";
    static final String FLIGHT_CODE = "flight_code";
    static final String INSTRUCTOR_REQUIRED = "instructor_required";
    static final String OBSERVER_PILOT_OR_INSTRUCTOR_REQUIRED =
            "observer_pilot_or_instructor_required";
    static final String IS_CHECK_FLIGHT = "is_check_flight";
    static final String IS_PASSENGER_FLIGHT = "is_passenger_flight";
    static final String IS_SOLO_FLIGHT = "is_solo_flight";
    static final String IS_FOR_GLIDER_FLIGHTS = "is_for_glider_flights";
    static final String IS_FOR_TOW_FLIGHTS = "is_for_tow_flights";
    static final String IS_FOR_MOTOR_FLIGHTS = "is_for_motor_flights";
    static final String IS_FLIGHT_COST_BALANCE_SELECTABLE = "is_flight_cost_balance_selectable";
    static final String IS_COUPON_NUMBER_REQUIRED = "is_coupon_number_required";
    static final String IS_FOR_AIRCRAFT_RESERVATION_TYPE = "is_for_aircraft_reservation_type";
    static final String MIN_NR_OF_AIRCRAFT_SEATS_REQUIRED = "min_nr_of_aircraft_seats_required";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            LEGACY_GUID, OPERATING_CLUB_ID, FLIGHT_TYPE_NAME, FLIGHT_CODE,
            INSTRUCTOR_REQUIRED, OBSERVER_PILOT_OR_INSTRUCTOR_REQUIRED,
            IS_CHECK_FLIGHT, IS_PASSENGER_FLIGHT, IS_SOLO_FLIGHT,
            IS_FOR_GLIDER_FLIGHTS, IS_FOR_TOW_FLIGHTS, IS_FOR_MOTOR_FLIGHTS,
            IS_FLIGHT_COST_BALANCE_SELECTABLE, IS_COUPON_NUMBER_REQUIRED,
            IS_FOR_AIRCRAFT_RESERVATION_TYPE, MIN_NR_OF_AIRCRAFT_SEATS_REQUIRED,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.FLIGHT_TYPE;
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
        // operating_club_id (the @TenantId) is off-convention for the CLUB FK —
        // the resolver's default would derive club_id and never rewrite the
        // legacy GUID, leaving fk_flight_type_operating_club_id unresolved at
        // INSERT (caught by FlightMigrationRoundTripIT, J-2 T-07).
        return List.of(new ForeignKeyColumn(OPERATING_CLUB_ID, EntityType.CLUB));
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("FlightTypeId"));
        target.writeStringField(OPERATING_CLUB_ID, source.getString("ClubId"));
        target.writeStringField(FLIGHT_TYPE_NAME, source.getString("FlightTypeName"));
        Coercions.writeOptionalString(target, FLIGHT_CODE, source.getString("FlightCode"));
        target.writeBooleanField(INSTRUCTOR_REQUIRED, source.getBoolean("InstructorRequired"));
        target.writeBooleanField(OBSERVER_PILOT_OR_INSTRUCTOR_REQUIRED,
                source.getBoolean("ObserverPilotOrInstructorRequired"));
        target.writeBooleanField(IS_CHECK_FLIGHT, source.getBoolean("IsCheckFlight"));
        target.writeBooleanField(IS_PASSENGER_FLIGHT, source.getBoolean("IsPassengerFlight"));
        target.writeBooleanField(IS_SOLO_FLIGHT, source.getBoolean("IsSoloFlight"));
        target.writeBooleanField(IS_FOR_GLIDER_FLIGHTS, source.getBoolean("IsForGliderFlights"));
        target.writeBooleanField(IS_FOR_TOW_FLIGHTS, source.getBoolean("IsForTowFlights"));
        target.writeBooleanField(IS_FOR_MOTOR_FLIGHTS, source.getBoolean("IsForMotorFlights"));
        target.writeBooleanField(IS_FLIGHT_COST_BALANCE_SELECTABLE,
                source.getBoolean("IsFlightCostBalanceSelectable"));
        target.writeBooleanField(IS_COUPON_NUMBER_REQUIRED,
                source.getBoolean("IsCouponNumberRequired"));
        target.writeBooleanField(IS_FOR_AIRCRAFT_RESERVATION_TYPE,
                source.getBoolean("IsForAircraftReservationType"));
        Coercions.writeOptionalInt(target, MIN_NR_OF_AIRCRAFT_SEATS_REQUIRED,
                source.getObject("MinNrOfAircraftSeatsRequired", Integer.class));
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
        target.setString(position++, source.get(FLIGHT_TYPE_NAME).asText());
        target.setString(position++, Coercions.readStringOrNull(source, FLIGHT_CODE));
        target.setObject(position++, source.get(INSTRUCTOR_REQUIRED).asBoolean());
        target.setObject(position++, source.get(OBSERVER_PILOT_OR_INSTRUCTOR_REQUIRED).asBoolean());
        target.setObject(position++, source.get(IS_CHECK_FLIGHT).asBoolean());
        target.setObject(position++, source.get(IS_PASSENGER_FLIGHT).asBoolean());
        target.setObject(position++, source.get(IS_SOLO_FLIGHT).asBoolean());
        target.setObject(position++, source.get(IS_FOR_GLIDER_FLIGHTS).asBoolean());
        target.setObject(position++, source.get(IS_FOR_TOW_FLIGHTS).asBoolean());
        target.setObject(position++, source.get(IS_FOR_MOTOR_FLIGHTS).asBoolean());
        target.setObject(position++, source.get(IS_FLIGHT_COST_BALANCE_SELECTABLE).asBoolean());
        target.setObject(position++, source.get(IS_COUPON_NUMBER_REQUIRED).asBoolean());
        target.setObject(position++, source.get(IS_FOR_AIRCRAFT_RESERVATION_TYPE).asBoolean());
        target.setObject(position++,
                Coercions.readIntOrNull(source, MIN_NR_OF_AIRCRAFT_SEATS_REQUIRED));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
