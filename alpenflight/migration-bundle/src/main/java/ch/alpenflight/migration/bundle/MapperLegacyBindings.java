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
                    // ALL legacy ClubStates (System=0/Active=1/Passive=2/Inactive=3)
                    // map to a V2 code (ClubStateMapper.v2CodeForLegacyId, J-0c T-16),
                    // so every row must enter the catalogue stream — the CLUB NDJSON's
                    // club_state_id (= legacyIntIdToUuidString(ClubStateId)) resolves
                    // against legacy_id_map_club_state, which is built from THIS stream.
                    // Dropping id=0 here would leave a System club's FK unresolved.
                    "SELECT ClubStateId FROM ClubStates",
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
                    //
                    // LocationTypeId source: legacy Locations.LocationTypeId is a
                    // uniqueidentifier (GUID FK to LocationTypes), but LocationMapper
                    // reads it via getInt + legacyIntIdToUuidString — the
                    // legacy_int_id resolution (t_location_type.legacy_int_id =
                    // 1,2,3,4,5,99) keys on the int LocationTypes.LocationTypeCupId,
                    // NOT the GUID. So JOIN LocationTypes and project the int CupId
                    // AS LocationTypeId (the column writeNdjson reads). INNER JOIN
                    // matches semantics: Location.LocationTypeId is non-null Guid
                    // (FLS.Server.Data/DbEntities/Location.cs:36), so every row has a
                    // type. (J-0c T-14 parity fix.)
                    """
                    SELECT l.LocationId, fanout.ClubId AS ClubId,
                           l.LocationName, l.LocationShortName, l.CountryId,
                           lt.LocationTypeCupId AS LocationTypeId,
                           l.IcaoCode, l.Latitude, l.Longitude,
                           l.Elevation, l.ElevationUnitType, l.RunwayDirection,
                           l.RunwayLength, l.RunwayLengthUnitType, l.AirportFrequency,
                           l.Description, l.SortIndicator,
                           l.IsInboundRouteRequired, l.IsOutboundRouteRequired,
                           l.IsFastEntryRecord,
                           l.CreatedOn, l.CreatedByUserId, l.ModifiedOn,
                           l.ModifiedByUserId, l.DeletedOn, l.DeletedByUserId
                    FROM Locations l
                    JOIN LocationTypes lt ON lt.LocationTypeId = l.LocationTypeId
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
                    // Fan-out (J-0b): id (the derived per-replica PK) and
                    // legacy_guid (the shared legacy LocationId) are SEPARATE
                    // destination columns — the producer emits both, matching
                    // LocationMapper.columns() order. No legacy_guid → id alias.
                    """
                    INSERT INTO t_location (
                      id, legacy_guid, club_id, location_name, location_short_name,
                      country_id, location_type_id, icao_code, latitude, longitude,
                      elevation, elevation_unit_type_id, runway_direction,
                      runway_length, runway_length_unit_type_id, airport_frequency,
                      description, sort_indicator,
                      is_inbound_route_required, is_outbound_route_required,
                      is_fast_entry_record,
                      created_on, created_by_user_id, modified_on,
                      modified_by_user_id, deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?, ?, ?,
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
                    // location_id (no own club_id column). But the parent Location
                    // is a fan-out target keyed (legacy_guid, club_id): the shared
                    // legacy GUID is identical across replicas, so location_id alone
                    // cannot disambiguate which per-club replica the child means.
                    // The child therefore FANS OUT too — one row per (legacy IOP,
                    // partner club) — joining its parent Location's fan-out partner
                    // set (the SAME union the LOCATION binding uses: Clubs.HomebaseId
                    // + Flights start/landing locations via OwnerId = ClubId). The
                    // partner club's id is aliased AS ClubId (the child's own legacy
                    // club, read by InOutboundPointMapper) so writeNdjson derives the
                    // per-replica id and emits the resolver-only club_id for T-07's
                    // composite (location_id, club_id) FK lookup. DISTINCT in the
                    // partner sub-select dedupes a club that references the parent
                    // through several flights.
                    """
                    SELECT iop.InOutboundPointId, iop.LocationId,
                           fanout.ClubId AS ClubId,
                           iop.InOutboundPointName,
                           iop.IsInboundPoint, iop.IsOutboundPoint,
                           iop.CreatedOn, iop.CreatedByUserId, iop.ModifiedOn,
                           iop.ModifiedByUserId, iop.DeletedOn, iop.DeletedByUserId
                    FROM InOutboundPoints iop
                    JOIN (
                        SELECT DISTINCT HomebaseId AS LocationId, ClubId
                        FROM Clubs WHERE HomebaseId IS NOT NULL
                        UNION
                        SELECT DISTINCT StartLocationId AS LocationId, OwnerId AS ClubId
                        FROM Flights WHERE StartLocationId IS NOT NULL
                        UNION
                        SELECT DISTINCT LdgLocationId AS LocationId, OwnerId AS ClubId
                        FROM Flights WHERE LdgLocationId IS NOT NULL
                    ) fanout ON fanout.LocationId = iop.LocationId
                    """,
                    "t_inoutbound_point",
                    // Fan-out (J-0b): id (the derived per-replica PK) and legacy_guid
                    // (the shared legacy IOP id) are SEPARATE destination columns,
                    // matching InOutboundPointMapper.columns() order. club_id is NOT
                    // an INSERT column — it is a resolver-only wire field consumed by
                    // T-07's ForeignKeyResolver, never persisted.
                    """
                    INSERT INTO t_inoutbound_point (
                      id, legacy_guid, location_id, point_name, point_type, direction,
                      description,
                      created_on, created_by_user_id, modified_on,
                      modified_by_user_id, deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?, ?, ?, ?,
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
