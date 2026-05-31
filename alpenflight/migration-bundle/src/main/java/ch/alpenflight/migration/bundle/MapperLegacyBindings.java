package ch.alpenflight.migration.bundle;

import java.util.Map;

/**
 * Per-entity binding between the new-stack {@link EntityType} and the
 * legacy MSSQL artifacts the producer reads + the Postgres artifacts the
 * consumer writes. Each entry holds:
 *
 * <ul>
 *   <li><strong>{@code SELECT}</strong> for the producer — the legacy
 *       table + column list the mapper's {@code writeNdjson} reads. Hand-
 *       curated against the FLSTest schema applied by
 *       {@code FlsTestSchemaApplier} so a legacy column rename is caught
 *       by a {@code SELECT} failure rather than a silent {@code NULL}.</li>
 *   <li><strong>{@link PortPolicy}</strong> — drives the consumer's
 *       routing. {@code SYSTEM_GLOBAL} entries feed
 *       {@code LegacyIdMapPopulator}; {@code FULL_PORT} entries feed
 *       {@link Mapper#readEntity} after FK rewriting.</li>
 *   <li><strong>{@code INSERT}</strong> for the FULL_PORT consumer — the
 *       new-stack table + parameterised column list matching
 *       {@code Mapper.columns()} parameter order, with {@code legacy_guid}
 *       wire-name resolved to the destination's {@code id} column.</li>
 * </ul>
 *
 * <p>Bound today — COUNTRY, LANGUAGE, CLUB_STATE, CLUB, USER (the IDENTITY
 * group) plus LOCATION + INOUTBOUND_POINT (the J-0 tenant-scoped FLIGHT-group
 * pair; T-02c). Both the export jar (S-139) and the parity ProducerHarness
 * consume these bindings. Later journeys extend the registry to the remaining
 * entities (the Aircraft/Flight group at J-1/J-2).
 */
public final class MapperLegacyBindings {

    public enum PortPolicy { FULL_PORT, SYSTEM_GLOBAL }

    public record Binding(
            PortPolicy portPolicy,
            String legacySelect,
            String newSchemaTable,
            String newSchemaInsert) {
    }

    private static final Map<EntityType, Binding> BINDINGS = Map.of(
            EntityType.COUNTRY, new Binding(
                    PortPolicy.SYSTEM_GLOBAL,
                    "SELECT CountryId, CountryCodeIso2 FROM Countries",
                    "t_country",
                    ""),
            EntityType.LANGUAGE, new Binding(
                    PortPolicy.SYSTEM_GLOBAL,
                    "SELECT LanguageId, LanguageKey FROM Languages",
                    "t_language",
                    ""),
            EntityType.CLUB_STATE, new Binding(
                    PortPolicy.SYSTEM_GLOBAL,
                    // ClubStateId=0 ("System") has no V2 destination; filter
                    // structurally per ClubStateMapper class Javadoc.
                    "SELECT ClubStateId FROM ClubStates WHERE ClubStateId <> 0",
                    "t_club_state",
                    ""),
            EntityType.CLUB, new Binding(
                    PortPolicy.FULL_PORT,
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
                    PortPolicy.FULL_PORT,
                    // UserMapper class Javadoc pins the system-actor filter as
                    // an ADR 0007 invariant — the bundle MUST NOT carry the
                    // legacy ASP.NET Identity system user.
                    """
                    SELECT UserId, ClubId, UserName, FriendlyName, PersonId,
                           NotificationEmail, PhoneNumber, Remarks, LanguageId,
                           CreatedOn, CreatedByUserId,
                           ModifiedOn, ModifiedByUserId, DeletedOn, DeletedByUserId
                    FROM Users
                    WHERE UserId <> '13731EE2-C1D8-455C-8AD1-C39399893FFF'
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
                    """),
            EntityType.LOCATION, new Binding(
                    PortPolicy.FULL_PORT,
                    // Legacy `Locations` is shared (no own ClubId); the new
                    // schema is tenant-scoped (club_id IS the @TenantId per V7).
                    // The producer FANS OUT each legacy row into one row per
                    // referencing Club, aliasing the partner Club's id AS ClubId
                    // (LocationMapper reads it verbatim). The referencing-Club
                    // set is the union of Clubs.HomebaseId and Flights'
                    // start/landing locations resolved through the legacy
                    // club-ownership convention Flights.OwnerId = ClubId
                    // (FlightReportService.cs:123). DISTINCT dedupes the
                    // (Location, Club) pair when a club references one Location
                    // through several flights.
                    //
                    // NOT-YET-bound fan-out source: Aircrafts.HomebaseId →
                    // Aircraft's computed managing_club_id. That cascade is
                    // AircraftMapper's producer logic (unbound; J-1). A Location
                    // referenced ONLY by an aircraft homebase — no club homebase,
                    // no flights — would miss that club's replica until the
                    // Aircraft binding lands. Tracked there, not papered over.
                    """
                    SELECT l.LocationId, fanout.ClubId AS ClubId,
                           l.LocationName, l.LocationShortName, l.CountryId,
                           l.LocationTypeId, l.IcaoCode, l.Latitude, l.Longitude,
                           l.Elevation, l.ElevationUnitType, l.RunwayDirection,
                           l.RunwayLength, l.RunwayLengthUnitType, l.AirportFrequency,
                           l.Description, l.SortIndicator,
                           l.IsInboundRouteRequired, l.IsOutboundRouteRequired,
                           l.IsFastEntryRecord,
                           l.CreatedOn, l.CreatedByUserId, l.ModifiedOn,
                           l.ModifiedByUserId, l.DeletedOn, l.DeletedByUserId
                    FROM Locations l
                    JOIN (
                        SELECT DISTINCT HomebaseId AS LocationId, ClubId
                        FROM Clubs WHERE HomebaseId IS NOT NULL
                        UNION
                        SELECT DISTINCT StartLocationId AS LocationId, OwnerId AS ClubId
                        FROM Flights WHERE StartLocationId IS NOT NULL
                        UNION
                        SELECT DISTINCT LdgLocationId AS LocationId, OwnerId AS ClubId
                        FROM Flights WHERE LdgLocationId IS NOT NULL
                    ) fanout ON fanout.LocationId = l.LocationId
                    """,
                    "t_location",
                    """
                    INSERT INTO t_location (
                      id, club_id, location_name, location_short_name,
                      country_id, location_type_id, icao_code, latitude, longitude,
                      elevation, elevation_unit_type_id, runway_direction,
                      runway_length, runway_length_unit_type_id, airport_frequency,
                      description, sort_indicator,
                      is_inbound_route_required, is_outbound_route_required,
                      is_fast_entry_record,
                      created_on, created_by_user_id, modified_on,
                      modified_by_user_id, deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?, ?,
                            ?, ?, ?, ?, ?,
                            ?, ?, ?,
                            ?, ?, ?,
                            ?, ?,
                            ?, ?,
                            ?,
                            ?, ?, ?,
                            ?, ?, ?)
                    """),
            EntityType.INOUTBOUND_POINT, new Binding(
                    PortPolicy.FULL_PORT,
                    // Aggregate-internal child of Location; tenancy inherited via
                    // location_id (no own ClubId). The parent fan-out emits one
                    // child row per fanned-out parent replica downstream, so the
                    // SELECT is a straight projection of the legacy child row.
                    """
                    SELECT InOutboundPointId, LocationId, InOutboundPointName,
                           IsInboundPoint, IsOutboundPoint,
                           CreatedOn, CreatedByUserId, ModifiedOn,
                           ModifiedByUserId, DeletedOn, DeletedByUserId
                    FROM InOutboundPoints
                    """,
                    "t_inoutbound_point",
                    """
                    INSERT INTO t_inoutbound_point (
                      id, location_id, point_name, point_type, direction,
                      description,
                      created_on, created_by_user_id, modified_on,
                      modified_by_user_id, deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?, ?, ?,
                            ?,
                            ?, ?, ?,
                            ?, ?, ?)
                    """));

    private MapperLegacyBindings() { }

    public static Binding require(EntityType entity) {
        Binding binding = BINDINGS.get(entity);
        if (binding == null) {
            throw new IllegalArgumentException(
                    "No legacy binding registered for " + entity);
        }
        return binding;
    }

    public static boolean isRegistered(EntityType entity) {
        return BINDINGS.containsKey(entity);
    }

    public static PortPolicy portPolicy(EntityType entity) {
        return require(entity).portPolicy();
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
