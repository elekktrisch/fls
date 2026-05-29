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
import java.util.UUID;

/**
 * Tenant root: legacy {@code Clubs.ClubId} → {@code t_club.id}.
 *
 * <p>Outgoing structural FKs:
 * <ul>
 *   <li>{@code country_id} → COUNTRY (SYSTEM_GLOBAL)</li>
 *   <li>{@code club_state_id} → CLUB_STATE (SYSTEM_GLOBAL; INT → synthetic
 *       UUID via {@link Coercions#legacyIntIdToUuidString})</li>
 * </ul>
 *
 * <p>Flight-domain FKs ({@code homebase_id}, default {@code start_type_id}
 * / glider / motor / tow flight types) are added to {@code t_club} by V3's
 * {@code ALTER TABLE} (S-185 epic scope). They stay NULL at S-184 ingest;
 * S-185's flight-group mapper layer fills them in a second pass after the
 * FLIGHT-group entities exist.
 *
 * <p>Legacy ASP.NET artifacts dropped (no destination): {@code OwnerId},
 * {@code OwnershipType}, {@code RecordState}, {@code IsDeleted} (encoded
 * structurally via {@code deleted_on}).
 *
 * <p>{@code last_*_synchronisation_on} are operational caches resampled
 * post-ingest — marked {@link ParityIgnore} so S-187's sampled-value diff
 * does not assert equality on them.
 */
public final class ClubMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";
    static final String CLUBNAME = "clubname";
    static final String CLUB_KEY = "club_key";
    static final String ADDRESS = "address";
    static final String ZIP = "zip";
    static final String CITY = "city";
    static final String COUNTRY_ID = "country_id";
    static final String PHONE = "phone";
    static final String FAX_NUMBER = "fax_number";
    static final String EMAIL = "email";
    static final String WEB_PAGE = "web_page";
    static final String CONTACT = "contact";
    static final String CLUB_STATE_ID = "club_state_id";
    static final String SEND_AIRCRAFT_STATISTIC_REPORT_TO = "send_aircraft_statistic_report_to";
    static final String SEND_PLANNING_DAY_INFO_MAIL_TO = "send_planning_day_info_mail_to";
    static final String SEND_DELIVERY_MAIL_EXPORT_TO = "send_delivery_mail_export_to";
    static final String SEND_TRIAL_FLIGHT_REGISTRATION_OPERATOR_EMAIL =
            "send_trial_flight_registration_operator_email";
    static final String SEND_PASSENGER_FLIGHT_REGISTRATION_OPERATOR_EMAIL =
            "send_passenger_flight_registration_operator_email";
    static final String REPLY_TO_EMAIL_ADDRESS = "reply_to_email_address";
    static final String RUN_DELIVERY_CREATION_JOB = "run_delivery_creation_job";
    static final String RUN_DELIVERY_MAIL_EXPORT_JOB = "run_delivery_mail_export_job";

    @ParityIgnore
    static final String LAST_PERSON_SYNCHRONISATION_ON = "last_person_synchronisation_on";
    @ParityIgnore
    static final String LAST_DELIVERY_SYNCHRONISATION_ON = "last_delivery_synchronisation_on";
    @ParityIgnore
    static final String LAST_ARTICLE_SYNCHRONISATION_ON = "last_article_synchronisation_on";

    static final String IS_CLUB_MEMBER_NUMBER_READONLY = "is_club_member_number_readonly";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            LEGACY_GUID, CLUBNAME, CLUB_KEY, ADDRESS, ZIP, CITY, COUNTRY_ID,
            PHONE, FAX_NUMBER, EMAIL, WEB_PAGE, CONTACT, CLUB_STATE_ID,
            SEND_AIRCRAFT_STATISTIC_REPORT_TO, SEND_PLANNING_DAY_INFO_MAIL_TO,
            SEND_DELIVERY_MAIL_EXPORT_TO,
            SEND_TRIAL_FLIGHT_REGISTRATION_OPERATOR_EMAIL,
            SEND_PASSENGER_FLIGHT_REGISTRATION_OPERATOR_EMAIL,
            REPLY_TO_EMAIL_ADDRESS,
            RUN_DELIVERY_CREATION_JOB, RUN_DELIVERY_MAIL_EXPORT_JOB,
            LAST_PERSON_SYNCHRONISATION_ON, LAST_DELIVERY_SYNCHRONISATION_ON,
            LAST_ARTICLE_SYNCHRONISATION_ON,
            IS_CLUB_MEMBER_NUMBER_READONLY,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.CLUB;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        return List.of(EntityType.COUNTRY, EntityType.CLUB_STATE);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("ClubId"));
        target.writeStringField(CLUBNAME, source.getString("Clubname"));
        target.writeStringField(CLUB_KEY, source.getString("ClubKey"));
        Coercions.writeOptionalString(target, ADDRESS, source.getString("Address"));
        Coercions.writeOptionalString(target, ZIP, source.getString("Zip"));
        Coercions.writeOptionalString(target, CITY, source.getString("City"));
        target.writeStringField(COUNTRY_ID, source.getString("CountryId"));
        Coercions.writeOptionalString(target, PHONE, source.getString("Phone"));
        Coercions.writeOptionalString(target, FAX_NUMBER, source.getString("FaxNumber"));
        Coercions.writeOptionalString(target, EMAIL, source.getString("Email"));
        Coercions.writeOptionalString(target, WEB_PAGE, source.getString("WebPage"));
        Coercions.writeOptionalString(target, CONTACT, source.getString("Contact"));
        target.writeStringField(CLUB_STATE_ID,
                Coercions.legacyIntIdToUuidString(source.getInt("ClubStateId")));
        Coercions.writeOptionalString(target, SEND_AIRCRAFT_STATISTIC_REPORT_TO,
                source.getString("SendAircraftStatisticReportTo"));
        Coercions.writeOptionalString(target, SEND_PLANNING_DAY_INFO_MAIL_TO,
                source.getString("SendPlanningDayInfoMailTo"));
        Coercions.writeOptionalString(target, SEND_DELIVERY_MAIL_EXPORT_TO,
                source.getString("SendDeliveryMailExportTo"));
        Coercions.writeOptionalString(target, SEND_TRIAL_FLIGHT_REGISTRATION_OPERATOR_EMAIL,
                source.getString("SendTrialFlightRegistrationOperatorEmailTo"));
        Coercions.writeOptionalString(target, SEND_PASSENGER_FLIGHT_REGISTRATION_OPERATOR_EMAIL,
                source.getString("SendPassengerFlightRegistrationOperatorEmailTo"));
        Coercions.writeOptionalString(target, REPLY_TO_EMAIL_ADDRESS,
                source.getString("ReplyToEmailAddress"));
        target.writeBooleanField(RUN_DELIVERY_CREATION_JOB,
                source.getBoolean("RunDeliveryCreationJob"));
        target.writeBooleanField(RUN_DELIVERY_MAIL_EXPORT_JOB,
                source.getBoolean("RunDeliveryMailExportJob"));
        Coercions.writeOptionalTimestamp(target, LAST_PERSON_SYNCHRONISATION_ON,
                source.getTimestamp("LastPersonSynchronisationOn"));
        Coercions.writeOptionalTimestamp(target, LAST_DELIVERY_SYNCHRONISATION_ON,
                source.getTimestamp("LastDeliverySynchronisationOn"));
        Coercions.writeOptionalTimestamp(target, LAST_ARTICLE_SYNCHRONISATION_ON,
                source.getTimestamp("LastArticleSynchronisationOn"));
        target.writeBooleanField(IS_CLUB_MEMBER_NUMBER_READONLY,
                source.getBoolean("IsClubMemberNumberReadonly"));
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
        target.setString(position++, source.get(CLUBNAME).asText());
        target.setString(position++, source.get(CLUB_KEY).asText());
        target.setString(position++, Coercions.readStringOrNull(source, ADDRESS));
        target.setString(position++, Coercions.readStringOrNull(source, ZIP));
        target.setString(position++, Coercions.readStringOrNull(source, CITY));
        target.setObject(position++, UUID.fromString(source.get(COUNTRY_ID).asText()));
        target.setString(position++, Coercions.readStringOrNull(source, PHONE));
        target.setString(position++, Coercions.readStringOrNull(source, FAX_NUMBER));
        target.setString(position++, Coercions.readStringOrNull(source, EMAIL));
        target.setString(position++, Coercions.readStringOrNull(source, WEB_PAGE));
        target.setString(position++, Coercions.readStringOrNull(source, CONTACT));
        target.setObject(position++, UUID.fromString(source.get(CLUB_STATE_ID).asText()));
        target.setString(position++,
                Coercions.readStringOrNull(source, SEND_AIRCRAFT_STATISTIC_REPORT_TO));
        target.setString(position++,
                Coercions.readStringOrNull(source, SEND_PLANNING_DAY_INFO_MAIL_TO));
        target.setString(position++,
                Coercions.readStringOrNull(source, SEND_DELIVERY_MAIL_EXPORT_TO));
        target.setString(position++,
                Coercions.readStringOrNull(source, SEND_TRIAL_FLIGHT_REGISTRATION_OPERATOR_EMAIL));
        target.setString(position++,
                Coercions.readStringOrNull(source, SEND_PASSENGER_FLIGHT_REGISTRATION_OPERATOR_EMAIL));
        target.setString(position++, Coercions.readStringOrNull(source, REPLY_TO_EMAIL_ADDRESS));
        target.setObject(position++, source.get(RUN_DELIVERY_CREATION_JOB).asBoolean());
        target.setObject(position++, source.get(RUN_DELIVERY_MAIL_EXPORT_JOB).asBoolean());
        target.setTimestamp(position++,
                Coercions.readTimestampOrNull(source, LAST_PERSON_SYNCHRONISATION_ON));
        target.setTimestamp(position++,
                Coercions.readTimestampOrNull(source, LAST_DELIVERY_SYNCHRONISATION_ON));
        target.setTimestamp(position++,
                Coercions.readTimestampOrNull(source, LAST_ARTICLE_SYNCHRONISATION_ON));
        target.setObject(position++, source.get(IS_CLUB_MEMBER_NUMBER_READONLY).asBoolean());
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
