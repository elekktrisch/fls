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

public final class PersonMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";
    static final String LASTNAME = "lastname";
    static final String FIRSTNAME = "firstname";
    static final String MIDNAME = "midname";
    static final String COMPANY_NAME = "company_name";
    static final String ADDRESS_LINE1 = "address_line1";
    static final String ADDRESS_LINE2 = "address_line2";
    static final String ZIP = "zip";
    static final String CITY = "city";
    static final String REGION = "region";
    static final String COUNTRY_ID = "country_id";
    static final String PRIVATE_PHONE = "private_phone";
    static final String MOBILE_PHONE = "mobile_phone";
    static final String BUSINESS_PHONE = "business_phone";
    static final String FAX_NUMBER = "fax_number";
    static final String EMAIL_PRIVATE = "email_private";
    static final String EMAIL_BUSINESS = "email_business";
    static final String PREFER_MAIL_TO_BUSINESS_MAIL = "prefer_mail_to_business_mail";
    static final String BIRTHDAY = "birthday";
    static final String HAS_MOTOR_PILOT_LICENCE = "has_motor_pilot_licence";
    static final String HAS_TOW_PILOT_LICENCE = "has_tow_pilot_licence";
    static final String HAS_GLIDER_INSTRUCTOR_LICENCE = "has_glider_instructor_licence";
    static final String HAS_GLIDER_PILOT_LICENCE = "has_glider_pilot_licence";
    static final String HAS_GLIDER_TRAINEE_LICENCE = "has_glider_trainee_licence";
    static final String HAS_GLIDER_PAX_LICENCE = "has_glider_pax_licence";
    static final String HAS_TMG_LICENCE = "has_tmg_licence";
    static final String HAS_WINCH_OPERATOR_LICENCE = "has_winch_operator_licence";
    static final String HAS_MOTOR_INSTRUCTOR_LICENCE = "has_motor_instructor_licence";
    static final String HAS_PART_M_LICENCE = "has_part_m_licence";
    static final String LICENCE_NUMBER = "licence_number";
    static final String MEDICAL_CLASS1_EXPIRE_DATE = "medical_class1_expire_date";
    static final String MEDICAL_CLASS2_EXPIRE_DATE = "medical_class2_expire_date";
    static final String MEDICAL_LAPL_EXPIRE_DATE = "medical_lapl_expire_date";
    static final String GLIDER_INSTRUCTOR_LICENCE_EXPIRE_DATE =
            "glider_instructor_licence_expire_date";
    static final String MOTOR_INSTRUCTOR_LICENCE_EXPIRE_DATE =
            "motor_instructor_licence_expire_date";
    static final String PART_M_LICENCE_EXPIRE_DATE = "part_m_licence_expire_date";
    static final String HAS_GLIDER_TOWING_START_PERMISSION = "has_glider_towing_start_permission";
    static final String HAS_GLIDER_SELF_START_PERMISSION = "has_glider_self_start_permission";
    static final String HAS_GLIDER_WINCH_START_PERMISSION = "has_glider_winch_start_permission";
    static final String SPOT_LINK = "spot_link";
    static final String RECEIVE_OWNED_AIRCRAFT_STATISTIC_REPORTS =
            "receive_owned_aircraft_statistic_reports";
    static final String ENABLE_ADDRESS = "enable_address";
    static final String IS_FAST_ENTRY_RECORD = "is_fast_entry_record";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            LEGACY_GUID, LASTNAME, FIRSTNAME, MIDNAME, COMPANY_NAME,
            ADDRESS_LINE1, ADDRESS_LINE2, ZIP, CITY, REGION, COUNTRY_ID,
            PRIVATE_PHONE, MOBILE_PHONE, BUSINESS_PHONE, FAX_NUMBER,
            EMAIL_PRIVATE, EMAIL_BUSINESS, PREFER_MAIL_TO_BUSINESS_MAIL, BIRTHDAY,
            HAS_MOTOR_PILOT_LICENCE, HAS_TOW_PILOT_LICENCE,
            HAS_GLIDER_INSTRUCTOR_LICENCE, HAS_GLIDER_PILOT_LICENCE,
            HAS_GLIDER_TRAINEE_LICENCE, HAS_GLIDER_PAX_LICENCE, HAS_TMG_LICENCE,
            HAS_WINCH_OPERATOR_LICENCE, HAS_MOTOR_INSTRUCTOR_LICENCE,
            HAS_PART_M_LICENCE, LICENCE_NUMBER,
            MEDICAL_CLASS1_EXPIRE_DATE, MEDICAL_CLASS2_EXPIRE_DATE,
            MEDICAL_LAPL_EXPIRE_DATE,
            GLIDER_INSTRUCTOR_LICENCE_EXPIRE_DATE,
            MOTOR_INSTRUCTOR_LICENCE_EXPIRE_DATE,
            PART_M_LICENCE_EXPIRE_DATE,
            HAS_GLIDER_TOWING_START_PERMISSION,
            HAS_GLIDER_SELF_START_PERMISSION,
            HAS_GLIDER_WINCH_START_PERMISSION,
            SPOT_LINK, RECEIVE_OWNED_AIRCRAFT_STATISTIC_REPORTS,
            ENABLE_ADDRESS, IS_FAST_ENTRY_RECORD,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.PERSON;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        return List.of(EntityType.COUNTRY);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("PersonId"));
        target.writeStringField(LASTNAME, source.getString("Lastname"));
        target.writeStringField(FIRSTNAME, source.getString("Firstname"));
        Coercions.writeOptionalString(target, MIDNAME, source.getString("Midname"));
        Coercions.writeOptionalString(target, COMPANY_NAME, source.getString("CompanyName"));
        Coercions.writeOptionalString(target, ADDRESS_LINE1, source.getString("AddressLine1"));
        Coercions.writeOptionalString(target, ADDRESS_LINE2, source.getString("AddressLine2"));
        Coercions.writeOptionalString(target, ZIP, source.getString("Zip"));
        Coercions.writeOptionalString(target, CITY, source.getString("City"));
        Coercions.writeOptionalString(target, REGION, source.getString("Region"));
        Coercions.writeOptionalString(target, COUNTRY_ID, source.getString("CountryId"));
        Coercions.writeOptionalString(target, PRIVATE_PHONE, source.getString("PrivatePhone"));
        Coercions.writeOptionalString(target, MOBILE_PHONE, source.getString("MobilePhone"));
        Coercions.writeOptionalString(target, BUSINESS_PHONE, source.getString("BusinessPhone"));
        Coercions.writeOptionalString(target, FAX_NUMBER, source.getString("FaxNumber"));
        Coercions.writeOptionalString(target, EMAIL_PRIVATE, source.getString("EmailPrivate"));
        Coercions.writeOptionalString(target, EMAIL_BUSINESS, source.getString("EmailBusiness"));
        target.writeBooleanField(PREFER_MAIL_TO_BUSINESS_MAIL,
                source.getBoolean("PreferMailToBusinessMail"));
        Coercions.writeOptionalDate(target, BIRTHDAY, source.getDate("Birthday"));
        target.writeBooleanField(HAS_MOTOR_PILOT_LICENCE,
                source.getBoolean("HasMotorPilotLicence"));
        target.writeBooleanField(HAS_TOW_PILOT_LICENCE, source.getBoolean("HasTowPilotLicence"));
        target.writeBooleanField(HAS_GLIDER_INSTRUCTOR_LICENCE,
                source.getBoolean("HasGliderInstructorLicence"));
        target.writeBooleanField(HAS_GLIDER_PILOT_LICENCE,
                source.getBoolean("HasGliderPilotLicence"));
        target.writeBooleanField(HAS_GLIDER_TRAINEE_LICENCE,
                source.getBoolean("HasGliderTraineeLicence"));
        target.writeBooleanField(HAS_GLIDER_PAX_LICENCE,
                source.getBoolean("HasGliderPAXLicence"));
        target.writeBooleanField(HAS_TMG_LICENCE, source.getBoolean("HasTMGLicence"));
        target.writeBooleanField(HAS_WINCH_OPERATOR_LICENCE,
                source.getBoolean("HasWinchOperatorLicence"));
        target.writeBooleanField(HAS_MOTOR_INSTRUCTOR_LICENCE,
                source.getBoolean("HasMotorInstructorLicence"));
        target.writeBooleanField(HAS_PART_M_LICENCE, source.getBoolean("HasPartMLicence"));
        Coercions.writeOptionalString(target, LICENCE_NUMBER, source.getString("LicenceNumber"));
        Coercions.writeOptionalDate(target, MEDICAL_CLASS1_EXPIRE_DATE,
                source.getDate("MedicalClass1ExpireDate"));
        Coercions.writeOptionalDate(target, MEDICAL_CLASS2_EXPIRE_DATE,
                source.getDate("MedicalClass2ExpireDate"));
        Coercions.writeOptionalDate(target, MEDICAL_LAPL_EXPIRE_DATE,
                source.getDate("MedicalLaplExpireDate"));
        Coercions.writeOptionalDate(target, GLIDER_INSTRUCTOR_LICENCE_EXPIRE_DATE,
                source.getDate("GliderInstructorLicenceExpireDate"));
        Coercions.writeOptionalDate(target, MOTOR_INSTRUCTOR_LICENCE_EXPIRE_DATE,
                source.getDate("MotorInstructorLicenceExpireDate"));
        Coercions.writeOptionalDate(target, PART_M_LICENCE_EXPIRE_DATE,
                source.getDate("PartMLicenceExpireDate"));
        target.writeBooleanField(HAS_GLIDER_TOWING_START_PERMISSION,
                source.getBoolean("HasGliderTowingStartPermission"));
        target.writeBooleanField(HAS_GLIDER_SELF_START_PERMISSION,
                source.getBoolean("HasGliderSelfStartPermission"));
        target.writeBooleanField(HAS_GLIDER_WINCH_START_PERMISSION,
                source.getBoolean("HasGliderWinchStartPermission"));
        Coercions.writeOptionalString(target, SPOT_LINK, source.getString("SpotLink"));
        target.writeBooleanField(RECEIVE_OWNED_AIRCRAFT_STATISTIC_REPORTS,
                source.getBoolean("ReceiveOwnedAircraftStatisticReports"));
        target.writeBooleanField(ENABLE_ADDRESS, source.getBoolean("EnableAddress"));
        target.writeBooleanField(IS_FAST_ENTRY_RECORD, source.getBoolean("IsFastEntryRecord"));
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
        target.setString(position++, source.get(LASTNAME).asText());
        target.setString(position++, source.get(FIRSTNAME).asText());
        target.setString(position++, Coercions.readStringOrNull(source, MIDNAME));
        target.setString(position++, Coercions.readStringOrNull(source, COMPANY_NAME));
        target.setString(position++, Coercions.readStringOrNull(source, ADDRESS_LINE1));
        target.setString(position++, Coercions.readStringOrNull(source, ADDRESS_LINE2));
        target.setString(position++, Coercions.readStringOrNull(source, ZIP));
        target.setString(position++, Coercions.readStringOrNull(source, CITY));
        target.setString(position++, Coercions.readStringOrNull(source, REGION));
        target.setObject(position++, Coercions.readUuidOrNull(source, COUNTRY_ID));
        target.setString(position++, Coercions.readStringOrNull(source, PRIVATE_PHONE));
        target.setString(position++, Coercions.readStringOrNull(source, MOBILE_PHONE));
        target.setString(position++, Coercions.readStringOrNull(source, BUSINESS_PHONE));
        target.setString(position++, Coercions.readStringOrNull(source, FAX_NUMBER));
        target.setString(position++, Coercions.readStringOrNull(source, EMAIL_PRIVATE));
        target.setString(position++, Coercions.readStringOrNull(source, EMAIL_BUSINESS));
        target.setObject(position++, source.get(PREFER_MAIL_TO_BUSINESS_MAIL).asBoolean());
        target.setDate(position++, Coercions.readDateOrNull(source, BIRTHDAY));
        target.setObject(position++, source.get(HAS_MOTOR_PILOT_LICENCE).asBoolean());
        target.setObject(position++, source.get(HAS_TOW_PILOT_LICENCE).asBoolean());
        target.setObject(position++, source.get(HAS_GLIDER_INSTRUCTOR_LICENCE).asBoolean());
        target.setObject(position++, source.get(HAS_GLIDER_PILOT_LICENCE).asBoolean());
        target.setObject(position++, source.get(HAS_GLIDER_TRAINEE_LICENCE).asBoolean());
        target.setObject(position++, source.get(HAS_GLIDER_PAX_LICENCE).asBoolean());
        target.setObject(position++, source.get(HAS_TMG_LICENCE).asBoolean());
        target.setObject(position++, source.get(HAS_WINCH_OPERATOR_LICENCE).asBoolean());
        target.setObject(position++, source.get(HAS_MOTOR_INSTRUCTOR_LICENCE).asBoolean());
        target.setObject(position++, source.get(HAS_PART_M_LICENCE).asBoolean());
        target.setString(position++, Coercions.readStringOrNull(source, LICENCE_NUMBER));
        target.setDate(position++,
                Coercions.readDateOrNull(source, MEDICAL_CLASS1_EXPIRE_DATE));
        target.setDate(position++,
                Coercions.readDateOrNull(source, MEDICAL_CLASS2_EXPIRE_DATE));
        target.setDate(position++,
                Coercions.readDateOrNull(source, MEDICAL_LAPL_EXPIRE_DATE));
        target.setDate(position++,
                Coercions.readDateOrNull(source, GLIDER_INSTRUCTOR_LICENCE_EXPIRE_DATE));
        target.setDate(position++,
                Coercions.readDateOrNull(source, MOTOR_INSTRUCTOR_LICENCE_EXPIRE_DATE));
        target.setDate(position++,
                Coercions.readDateOrNull(source, PART_M_LICENCE_EXPIRE_DATE));
        target.setObject(position++, source.get(HAS_GLIDER_TOWING_START_PERMISSION).asBoolean());
        target.setObject(position++, source.get(HAS_GLIDER_SELF_START_PERMISSION).asBoolean());
        target.setObject(position++, source.get(HAS_GLIDER_WINCH_START_PERMISSION).asBoolean());
        target.setString(position++, Coercions.readStringOrNull(source, SPOT_LINK));
        target.setObject(position++, source.get(RECEIVE_OWNED_AIRCRAFT_STATISTIC_REPORTS).asBoolean());
        target.setObject(position++, source.get(ENABLE_ADDRESS).asBoolean());
        target.setObject(position++, source.get(IS_FAST_ENTRY_RECORD).asBoolean());
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
