package ch.alpenflight.migration.bundle.accounting;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.ParityIgnore;
import ch.alpenflight.migration.bundle.ParitySentinel;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public final class DeliveryMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";
    static final String OPERATING_CLUB_ID = "operating_club_id";

    @ParitySentinel
    static final String PROCESS_STATE_ID = "process_state_id";

    static final String FLIGHT_ID = "flight_id";
    static final String RECIPIENT_PERSON_ID = "recipient_person_id";
    static final String RECIPIENT_NAME = "recipient_name";
    static final String RECIPIENT_FIRSTNAME = "recipient_firstname";
    static final String RECIPIENT_LASTNAME = "recipient_lastname";
    static final String RECIPIENT_ADDRESS_LINE1 = "recipient_address_line1";
    static final String RECIPIENT_ADDRESS_LINE2 = "recipient_address_line2";
    static final String RECIPIENT_ZIP_CODE = "recipient_zip_code";
    static final String RECIPIENT_CITY = "recipient_city";
    static final String RECIPIENT_COUNTRY_NAME = "recipient_country_name";
    static final String RECIPIENT_PERSON_CLUB_MEMBER_NUMBER =
            "recipient_person_club_member_number";
    static final String DELIVERY_INFORMATION = "delivery_information";
    static final String ADDITIONAL_INFORMATION = "additional_information";

    @ParitySentinel
    static final String DELIVERY_NUMBER = "delivery_number";

    static final String DELIVERED_ON = "delivered_on";
    static final String BATCH_ID = "batch_id";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            LEGACY_GUID, OPERATING_CLUB_ID, PROCESS_STATE_ID,
            FLIGHT_ID, RECIPIENT_PERSON_ID,
            RECIPIENT_NAME, RECIPIENT_FIRSTNAME, RECIPIENT_LASTNAME,
            RECIPIENT_ADDRESS_LINE1, RECIPIENT_ADDRESS_LINE2,
            RECIPIENT_ZIP_CODE, RECIPIENT_CITY, RECIPIENT_COUNTRY_NAME,
            RECIPIENT_PERSON_CLUB_MEMBER_NUMBER,
            DELIVERY_INFORMATION, ADDITIONAL_INFORMATION,
            DELIVERY_NUMBER,
            DELIVERED_ON, BATCH_ID,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.DELIVERY;
    }

    @Override
    public String[] wireColumns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeyTargets() {
        return List.of(EntityType.CLUB, EntityType.FLIGHT, EntityType.PERSON);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("DeliveryId"));
        target.writeStringField(OPERATING_CLUB_ID, source.getString("ClubId"));
        target.writeNumberField(PROCESS_STATE_ID,
                (short) source.getInt("ResolvedProcessStateId"));
        Coercions.writeOptionalString(target, FLIGHT_ID, source.getString("FlightId"));
        Coercions.writeOptionalString(target, RECIPIENT_PERSON_ID,
                source.getString("RecipientPersonId"));
        Coercions.writeOptionalString(target, RECIPIENT_NAME, source.getString("RecipientName"));
        Coercions.writeOptionalString(target, RECIPIENT_FIRSTNAME,
                source.getString("RecipientFirstname"));
        Coercions.writeOptionalString(target, RECIPIENT_LASTNAME,
                source.getString("RecipientLastname"));
        Coercions.writeOptionalString(target, RECIPIENT_ADDRESS_LINE1,
                source.getString("RecipientAddressLine1"));
        Coercions.writeOptionalString(target, RECIPIENT_ADDRESS_LINE2,
                source.getString("RecipientAddressLine2"));
        Coercions.writeOptionalString(target, RECIPIENT_ZIP_CODE,
                source.getString("RecipientZipCode"));
        Coercions.writeOptionalString(target, RECIPIENT_CITY,
                source.getString("RecipientCity"));
        Coercions.writeOptionalString(target, RECIPIENT_COUNTRY_NAME,
                source.getString("RecipientCountryName"));
        Coercions.writeOptionalString(target, RECIPIENT_PERSON_CLUB_MEMBER_NUMBER,
                source.getString("RecipientPersonClubMemberNumber"));
        Coercions.writeOptionalString(target, DELIVERY_INFORMATION,
                source.getString("DeliveryInformation"));
        Coercions.writeOptionalString(target, ADDITIONAL_INFORMATION,
                source.getString("AdditionalInformation"));
        Coercions.writeOptionalString(target, DELIVERY_NUMBER,
                source.getString("DeliveryNumber"));
        Coercions.writeOptionalTimestamp(target, DELIVERED_ON,
                source.getTimestamp("DeliveredOn"));
        target.writeNumberField(BATCH_ID, source.getLong("BatchId"));
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
        target.setShort(position++, (short) source.get(PROCESS_STATE_ID).intValue());
        target.setObject(position++, Coercions.readUuidOrNull(source, FLIGHT_ID));
        target.setObject(position++, Coercions.readUuidOrNull(source, RECIPIENT_PERSON_ID));
        target.setString(position++, Coercions.readStringOrNull(source, RECIPIENT_NAME));
        target.setString(position++, Coercions.readStringOrNull(source, RECIPIENT_FIRSTNAME));
        target.setString(position++, Coercions.readStringOrNull(source, RECIPIENT_LASTNAME));
        target.setString(position++, Coercions.readStringOrNull(source, RECIPIENT_ADDRESS_LINE1));
        target.setString(position++, Coercions.readStringOrNull(source, RECIPIENT_ADDRESS_LINE2));
        target.setString(position++, Coercions.readStringOrNull(source, RECIPIENT_ZIP_CODE));
        target.setString(position++, Coercions.readStringOrNull(source, RECIPIENT_CITY));
        target.setString(position++, Coercions.readStringOrNull(source, RECIPIENT_COUNTRY_NAME));
        target.setString(position++,
                Coercions.readStringOrNull(source, RECIPIENT_PERSON_CLUB_MEMBER_NUMBER));
        target.setString(position++, Coercions.readStringOrNull(source, DELIVERY_INFORMATION));
        target.setString(position++, Coercions.readStringOrNull(source, ADDITIONAL_INFORMATION));
        target.setString(position++, Coercions.readStringOrNull(source, DELIVERY_NUMBER));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELIVERED_ON));
        target.setLong(position++, source.get(BATCH_ID).longValue());
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
