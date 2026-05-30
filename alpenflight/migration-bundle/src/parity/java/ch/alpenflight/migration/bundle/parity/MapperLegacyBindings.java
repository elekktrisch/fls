package ch.alpenflight.migration.bundle.parity;

import ch.alpenflight.migration.bundle.EntityType;
import java.util.Map;

/**
 * Per-entity binding between the new-stack {@link EntityType} and the legacy
 * MSSQL artifacts a mapper reads + writes back into. Each entry holds:
 *
 * <ul>
 *   <li><strong>{@code SELECT}</strong> for the producer — the legacy table
 *       + column list the mapper's {@code writeNdjson} reads from. Hand-
 *       curated so a column rename in legacy is caught by a {@code SELECT}
 *       failure rather than a silent {@code NULL}.</li>
 *   <li><strong>{@code INSERT}</strong> for the consumer — the new-stack
 *       table + parameterised column list matching {@code Mapper.columns()}
 *       parameter order.</li>
 *   <li>The new-stack table name — used by the diff engine for row-count
 *       queries.</li>
 * </ul>
 *
 * <p>Vertical-slice scope (S-187): only the three mappers exercised by the
 * harness are bound here. S-187a extends the registry to the remaining 25.
 */
public final class MapperLegacyBindings {

    public record Binding(String legacySelect, String newSchemaTable, String newSchemaInsert) {
    }

    private static final Map<EntityType, Binding> BINDINGS = Map.of(
            EntityType.COUNTRY, new Binding(
                    "SELECT CountryId, CountryCodeIso2 FROM Countries",
                    "t_country",
                    // SYSTEM_GLOBAL_RESOLVE: the bundle carries (legacy_guid,
                    // iso2_code) pairs and the consumer populates
                    // legacy_id_map_country by joining destination on
                    // iso2_code (per S-141). The harness asserts the bundle
                    // bytes are well-formed; no INSERT into t_country happens
                    // because the destination rows are owned by V2.
                    "/* SYSTEM_GLOBAL_RESOLVE — no insert; bundle bytes only */"),
            EntityType.CLUB, new Binding(
                    """
                    SELECT ClubId, Clubname, ClubKey, Address, Zip, City, CountryId,
                           Phone, FaxNumber, Email, WebPage, Contact, ClubStateId,
                           SendAircraftStatisticReportTo, SendPlanningDayInfoMailTo,
                           SendDeliveryMailExportTo,
                           SendTrialFlightRegistrationOperatorEmailTo,
                           SendPassengerFlightRegistrationOperatorEmailTo,
                           ReplyToEmailAddress,
                           RunDeliveryCreationJob, RunDeliveryMailExportJob,
                           LastPersonSynchronisationOn, LastDeliverySynchronisationOn,
                           LastArticleSynchronisationOn,
                           IsClubMemberNumberReadonly,
                           CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                           DeletedOn, DeletedByUserId
                    FROM Clubs
                    """,
                    "t_club",
                    """
                    INSERT INTO t_club (
                      id, clubname, club_key, address, zip, city, country_id,
                      phone, fax_number, email, web_page, contact, club_state_id,
                      send_aircraft_statistic_report_to, send_planning_day_info_mail_to,
                      send_delivery_mail_export_to,
                      send_trial_flight_registration_operator_email,
                      send_passenger_flight_registration_operator_email,
                      reply_to_email_address,
                      run_delivery_creation_job, run_delivery_mail_export_job,
                      last_person_synchronisation_on, last_delivery_synchronisation_on,
                      last_article_synchronisation_on,
                      is_club_member_number_readonly,
                      created_on, created_by_user_id,
                      modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?)
                    """),
            EntityType.USER, new Binding(
                    """
                    SELECT UserId, ClubId, UserName, FriendlyName, PersonId,
                           NotificationEmail, PhoneNumber, Remarks, LanguageId,
                           CreatedOn, CreatedByUserId,
                           ModifiedOn, ModifiedByUserId, DeletedOn, DeletedByUserId
                    FROM Users
                    """,
                    "t_user",
                    """
                    INSERT INTO t_user (
                      id, club_id, username, friendly_name, person_id,
                      notification_email, phone_number, remarks, language_id,
                      keycloak_sub,
                      created_on, created_by_user_id,
                      modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?, ?, ?,
                            ?, ?, ?, ?,
                            ?,
                            ?, ?, ?, ?, ?, ?)
                    """));

    private MapperLegacyBindings() { }

    public static Binding require(EntityType entity) {
        Binding binding = BINDINGS.get(entity);
        if (binding == null) {
            throw new IllegalArgumentException(
                    "No legacy binding registered for " + entity + ". Vertical-slice "
                            + "S-187 covers COUNTRY, CLUB, USER; S-187a adds the remaining "
                            + "25 mappers. Add a binding here when introducing a new mapper "
                            + "into the harness.");
        }
        return binding;
    }

    public static boolean isRegistered(EntityType entity) {
        return BINDINGS.containsKey(entity);
    }

    public static String selectForProducer(EntityType entity) {
        return require(entity).legacySelect();
    }

    public static String insertForConsumer(EntityType entity) {
        return require(entity).newSchemaInsert();
    }

    public static String newSchemaTable(EntityType entity) {
        return require(entity).newSchemaTable();
    }
}
