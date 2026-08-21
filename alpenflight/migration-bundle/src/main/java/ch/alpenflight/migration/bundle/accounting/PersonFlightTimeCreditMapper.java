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
import java.util.Set;
import java.util.UUID;

public final class PersonFlightTimeCreditMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";
    static final String PERSON_ID = "person_id";
    static final String NO_FLIGHT_TIME_LIMIT = "no_flight_time_limit";
    static final String VALID_UNTIL = "valid_until";
    static final String USE_RULE_FOR_ALL_AIRCRAFTS_EXCEPT_LISTED =
            "use_rule_for_all_aircrafts_except_listed";
    static final String MATCHED_AIRCRAFT_IMMATRICULATIONS = "matched_aircraft_immatriculations";
    static final String DISCOUNT_IN_PERCENT = "discount_in_percent";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            LEGACY_GUID, PERSON_ID,
            NO_FLIGHT_TIME_LIMIT, VALID_UNTIL,
            USE_RULE_FOR_ALL_AIRCRAFTS_EXCEPT_LISTED, MATCHED_AIRCRAFT_IMMATRICULATIONS,
            DISCOUNT_IN_PERCENT,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.PERSON_FLIGHT_TIME_CREDIT;
    }

    @Override
    public String[] wireColumns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeyTargets() {
        return List.of(EntityType.PERSON);
    }

    @Override
    public Set<String> crossTenantForeignKeyColumns() {
        return Set.of(PERSON_ID);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("PersonFlightTimeCreditId"));
        target.writeStringField(PERSON_ID, source.getString("PersonId"));
        target.writeBooleanField(NO_FLIGHT_TIME_LIMIT, source.getBoolean("NoFlightTimeLimit"));
        Coercions.writeRequiredTimestamp(target, VALID_UNTIL, source.getTimestamp("ValidUntil"));
        target.writeBooleanField(USE_RULE_FOR_ALL_AIRCRAFTS_EXCEPT_LISTED,
                source.getBoolean("UseRuleForAllAircraftsExceptListed"));
        Coercions.writeOptionalString(target, MATCHED_AIRCRAFT_IMMATRICULATIONS,
                source.getString("MatchedAircraftImmatriculations"));
        target.writeNumberField(DISCOUNT_IN_PERCENT, source.getInt("DiscountInPercent"));
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
        target.setObject(position++, UUID.fromString(source.get(PERSON_ID).asText()));
        target.setBoolean(position++, source.get(NO_FLIGHT_TIME_LIMIT).asBoolean());
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, VALID_UNTIL));
        target.setBoolean(position++,
                source.get(USE_RULE_FOR_ALL_AIRCRAFTS_EXCEPT_LISTED).asBoolean());
        target.setString(position++,
                Coercions.readStringOrNull(source, MATCHED_AIRCRAFT_IMMATRICULATIONS));
        target.setInt(position++, source.get(DISCOUNT_IN_PERCENT).intValue());
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
