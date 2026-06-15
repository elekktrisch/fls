package ch.alpenflight.migration.bundle;

import static java.util.Map.entry;

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

    private static final Map<EntityType, Binding> BINDINGS = Map.ofEntries(
            entry(EntityType.COUNTRY, new Binding(
                    PortPolicy.SYSTEM_GLOBAL,
                    "SELECT CountryId, CountryCodeIso2 FROM Countries",
                    "t_country",
                    "")),
            entry(EntityType.LANGUAGE, new Binding(
                    PortPolicy.SYSTEM_GLOBAL,
                    "SELECT LanguageId, LanguageKey FROM Languages",
                    "t_language",
                    "")),
            entry(EntityType.CLUB_STATE, new Binding(
                    PortPolicy.SYSTEM_GLOBAL,
                    // ALL legacy ClubStates (System=0/Active=1/Passive=2/Inactive=3)
                    // map to a V2 code (ClubStateMapper.v2CodeForLegacyId, J-0c T-16),
                    // so every row must enter the catalogue stream — the CLUB NDJSON's
                    // club_state_id (= legacyIntIdToUuidString(ClubStateId)) resolves
                    // against legacy_id_map_club_state, which is built from THIS stream.
                    // Dropping id=0 here would leave a System club's FK unresolved.
                    "SELECT ClubStateId FROM ClubStates",
                    "t_club_state",
                    "")),
            entry(EntityType.CLUB, new Binding(
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
                    """)),
            entry(EntityType.PERSON, new Binding(
                    PortPolicy.FULL_PORT,
                    // Cross-tenant aggregate root (ADR 0008): legacy `Persons` has
                    // no own ClubId and t_person has NO club_id column, so there is
                    // no tenancy/fan-out projection here (unlike LOCATION). The only
                    // outgoing FK is the NULLABLE CountryId -> Countries, emitted as
                    // the legacy GUID and resolved through legacy_id_map_COUNTRY (the
                    // SYSTEM_GLOBAL seed map, T-15 mechanism) because
                    // PersonMapper.foreignKeys() declares COUNTRY. No system-actor
                    // exclusion: unlike USER (ADR 0007 system identity), no system
                    // Person exists in the legacy static data, and PersonMapper drops
                    // the legacy ASP.NET artifacts (OwnerId/OwnershipType/RecordState/
                    // IsDeleted) rather than filtering on them. Projects exactly the
                    // columns PersonMapper.writeNdjson reads — note the British-spelled
                    // LicenceNumber (DBUpdate_v1.8.1) and the Has*Licence /
                    // Has*StartPermission / Medical*ExpireDate columns the later
                    // DBUpdate scripts add to the v1.0 base Persons table.
                    """
                    SELECT PersonId, Lastname, Firstname, Midname, CompanyName,
                           AddressLine1, AddressLine2, Zip, City, Region, CountryId,
                           PrivatePhone, MobilePhone, BusinessPhone, FaxNumber,
                           EmailPrivate, EmailBusiness, PreferMailToBusinessMail, Birthday,
                           HasMotorPilotLicence, HasTowPilotLicence,
                           HasGliderInstructorLicence, HasGliderPilotLicence,
                           HasGliderTraineeLicence, HasGliderPAXLicence, HasTMGLicence,
                           HasWinchOperatorLicence, HasMotorInstructorLicence,
                           HasPartMLicence, LicenceNumber,
                           MedicalClass1ExpireDate, MedicalClass2ExpireDate,
                           MedicalLaplExpireDate,
                           GliderInstructorLicenceExpireDate,
                           MotorInstructorLicenceExpireDate, PartMLicenceExpireDate,
                           HasGliderTowingStartPermission, HasGliderSelfStartPermission,
                           HasGliderWinchStartPermission,
                           SpotLink, ReceiveOwnedAircraftStatisticReports,
                           EnableAddress, IsFastEntryRecord,
                           CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                           DeletedOn, DeletedByUserId
                    FROM Persons
                    """,
                    "t_person",
                    """
                    INSERT INTO t_person (
                      id, lastname, firstname, midname, company_name,
                      address_line1, address_line2, zip, city, region, country_id,
                      private_phone, mobile_phone, business_phone, fax_number,
                      email_private, email_business, prefer_mail_to_business_mail, birthday,
                      has_motor_pilot_licence, has_tow_pilot_licence,
                      has_glider_instructor_licence, has_glider_pilot_licence,
                      has_glider_trainee_licence, has_glider_pax_licence, has_tmg_licence,
                      has_winch_operator_licence, has_motor_instructor_licence,
                      has_part_m_licence, licence_number,
                      medical_class1_expire_date, medical_class2_expire_date,
                      medical_lapl_expire_date,
                      glider_instructor_licence_expire_date,
                      motor_instructor_licence_expire_date, part_m_licence_expire_date,
                      has_glider_towing_start_permission, has_glider_self_start_permission,
                      has_glider_winch_start_permission,
                      spot_link, receive_owned_aircraft_statistic_reports,
                      enable_address, is_fast_entry_record,
                      created_on, created_by_user_id, modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?,
                            ?, ?,
                            ?, ?, ?,
                            ?, ?,
                            ?, ?,
                            ?, ?,
                            ?,
                            ?,
                            ?, ?,
                            ?, ?,
                            ?,
                            ?, ?,
                            ?, ?,
                            ?, ?, ?, ?,
                            ?, ?)
                    """)),
            entry(EntityType.USER, new Binding(
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
                    """)),
            entry(EntityType.LOCATION, new Binding(
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
                    """)),
            entry(EntityType.INOUTBOUND_POINT, new Binding(
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
                    """)),
            entry(EntityType.AIRCRAFT, new Binding(
                    PortPolicy.FULL_PORT,
                    // Cross-tenant aggregate root (ADR 0008 2026-05-24 amendment):
                    // Aircraft are globally readable (legacy has no ClubId read
                    // filter, S-161 parity) but structurally tenant-scoped via
                    // managing_club_id (V10). Unlike LOCATION this is NOT a fan-out
                    // — one legacy Aircraft → exactly one t_aircraft row; legacy_guid
                    // resolves straight to id (like PERSON/CLUB), no per-club replica.
                    //
                    // managing_club_id (V10, NOT NULL) is producer-computed and
                    // aliased AS ManagingClubId on the cursor; AircraftMapper reads it
                    // verbatim. J-1 parity decision: the source is legacy
                    // AircraftOwnerClubId (the authored AircraftMapper cascade). The
                    // OwnerId-vs-AircraftOwnerClubId fidelity question is a recorded
                    // J-21/J-2 parity exclusion — not resolved here. A NULL
                    // AircraftOwnerClubId (private-person ownership) leaves
                    // managing_club_id NULL on the wire; the producer's drop+warn
                    // (AIRCRAFT_NO_MANAGING_CLUB) + the homebase-club fallback are
                    // S-139 producer logic, out of this SELECT's scope.
                    //
                    // aircraft_type_id source: AircraftMapper reads getInt("AircraftType")
                    // + legacyIntIdToUuidString — the int resolves to the V3-seeded
                    // t_aircraft_type.legacy_int_id. The real legacy column is
                    // AircraftType (Aircrafts DDL line 114; EF Aircraft.cs maps the C#
                    // property AircraftTypeId via [Column("AircraftType")]) — projected
                    // verbatim, no alias needed since the mapper key IS AircraftType.
                    // (T-16: the live export died on the prior AircraftTypeId alias —
                    // that column does not exist in the legacy schema.)
                    //
                    // homebase_id (HomebaseId → t_location) is the cross-tenant
                    // ride-through into the Location replica matching the Aircraft's
                    // managing club (the fan-out source the LOCATION binding flagged
                    // NOT-YET-bound; now wired here). Passed through as the legacy GUID,
                    // resolved by the FK rewriter against the managing-club replica.
                    //
                    // Legacy ASP.NET artifacts dropped (not projected): OwnerId,
                    // OwnershipType, RecordState, IsDeleted.
                    """
                    SELECT AircraftId, AircraftOwnerClubId AS ManagingClubId,
                           AircraftOwnerClubId,
                           AircraftType,
                           ManufacturerName, AircraftModel, Immatriculation,
                           CompetitionSign, FLARMId, AircraftSerialNumber,
                           YearOfManufacture, NoiseClass, NoiseLevel, MTOM, NrOfSeats,
                           AircraftOwnerPersonId,
                           FlightOperatingCounterUnitTypeId,
                           EngineOperatingCounterUnitTypeId,
                           HomebaseId, SpotLink,
                           IsTowingOrWinchRequired, IsTowingstartAllowed,
                           IsWinchstartAllowed, IsTowingAircraft, IsFastEntryRecord,
                           Comment, DaecIndex,
                           CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                           DeletedOn, DeletedByUserId
                    FROM Aircrafts
                    """,
                    "t_aircraft",
                    // Non-fan-out FULL_PORT: legacy_guid resolves to id (no separate
                    // legacy_guid column, unlike LOCATION). Column order matches
                    // AircraftMapper.columns(): id(legacy_guid), managing_club_id,
                    // owner_club_id, then the 30 attribute/audit columns. 33 params.
                    """
                    INSERT INTO t_aircraft (
                      id, managing_club_id, owner_club_id,
                      aircraft_type_id,
                      manufacturer_name, aircraft_model, immatriculation, competition_sign,
                      flarm_id, aircraft_serial_number, year_of_manufacture,
                      noise_class, noise_level, mtom, nr_of_seats,
                      aircraft_owner_person_id,
                      flight_operating_counter_unit_type_id,
                      engine_operating_counter_unit_type_id,
                      homebase_id, spot_link,
                      is_towing_or_winch_required, is_towing_start_allowed,
                      is_winch_start_allowed, is_towing_aircraft, is_fast_entry_record,
                      comment, daec_index,
                      created_on, created_by_user_id,
                      modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?,
                            ?,
                            ?, ?, ?, ?,
                            ?, ?, ?,
                            ?, ?, ?, ?,
                            ?,
                            ?,
                            ?,
                            ?, ?,
                            ?, ?,
                            ?, ?, ?,
                            ?, ?,
                            ?, ?,
                            ?, ?,
                            ?, ?)
                    """)),
            entry(EntityType.AIRCRAFT_AIRCRAFT_STATE, new Binding(
                    PortPolicy.FULL_PORT,
                    // Aircraft↔state history, aggregate-internal under Aircraft
                    // (cross-tenant). Legacy composite PK
                    // (AircraftId, AircraftStateId, ValidFrom) collapses to a
                    // surrogate UUID id minted at INSERT (V3 reshape) — so id is NOT
                    // a wire column and NOT projected; the SELECT projects only the
                    // 12 columns AircraftAircraftStateMapper reads. aircraft_state_id
                    // resolves through the V3-seeded t_aircraft_state.legacy_int_id:
                    // the mapper reads getInt("AircraftState"). The real legacy column
                    // is AircraftState (AircraftAircraftStates DDL line 77; EF
                    // AircraftAircraftState.cs maps the C# property AircraftStateId via
                    // [Column("AircraftState")]) — projected verbatim, no alias.
                    // (T-16: the prior AircraftStateId alias names a column that does
                    // not exist in the legacy schema — the same class of bug that
                    // aborted the live AIRCRAFT export on AircraftType.)
                    // noticed_by_person_id is a cross-tenant Person (Manifest
                    // TENANT_BYPASS_ALLOW_LIST). Legacy table has no IsDeleted column;
                    // deleted_on ports as NULL.
                    """
                    SELECT AircraftId, AircraftState,
                           ValidFrom, ValidTo, NoticedByPersonId, Remarks,
                           CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                           DeletedOn, DeletedByUserId
                    FROM AircraftAircraftStates
                    """,
                    "t_aircraft_aircraft_state",
                    // Surrogate id minted in-statement (gen_random_uuid()) — the
                    // legacy composite PK has no single-GUID to carry, and V3 forbids
                    // a DEFAULT on the column (app-owns-generation contract). The 12
                    // ? params follow AircraftAircraftStateMapper.columns() order,
                    // starting at aircraft_id (position 1).
                    """
                    INSERT INTO t_aircraft_aircraft_state (
                      id,
                      aircraft_id, aircraft_state_id, valid_from, valid_to,
                      noticed_by_person_id, remarks,
                      created_on, created_by_user_id,
                      modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (gen_random_uuid(),
                            ?, ?, ?, ?,
                            ?, ?,
                            ?, ?,
                            ?, ?,
                            ?, ?)
                    """)),
            entry(EntityType.AIRCRAFT_OPERATING_COUNTER, new Binding(
                    PortPolicy.FULL_PORT,
                    // Per-aircraft counter readings, aggregate-internal under
                    // Aircraft (cross-tenant): legacy AircraftOperatingCounterId →
                    // t_aircraft_operating_counter.id (its own legacy GUID, so
                    // legacy_guid resolves to id — non-fan-out, like AOC's parent).
                    // Only outgoing FK is the intra-aggregate aircraft_id. Legacy
                    // ASP.NET artifacts dropped: OwnerId, OwnershipType, RecordState,
                    // IsDeleted.
                    """
                    SELECT AircraftOperatingCounterId, AircraftId, AtDateTime,
                           TotalTowedGliderStarts, TotalWinchLaunchStarts, TotalSelfStarts,
                           FlightOperatingCounterInSeconds, EngineOperatingCounterInSeconds,
                           NextMaintenanceAtFlightOperatingCounterInSeconds,
                           NextMaintenanceAtEngineOperatingCounterInSeconds,
                           CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                           DeletedOn, DeletedByUserId
                    FROM AircraftOperatingCounters
                    """,
                    "t_aircraft_operating_counter",
                    // Non-fan-out: legacy_guid (AircraftOperatingCounterId) resolves
                    // to id. The 16 ? params follow
                    // AircraftOperatingCounterMapper.columns() order:
                    // id(legacy_guid), aircraft_id, then the readings + audit columns.
                    """
                    INSERT INTO t_aircraft_operating_counter (
                      id, aircraft_id, at_date_time,
                      total_towed_glider_starts, total_winch_launch_starts, total_self_starts,
                      flight_operating_counter_in_seconds, engine_operating_counter_in_seconds,
                      next_maintenance_at_flight_operating_counter_in_seconds,
                      next_maintenance_at_engine_operating_counter_in_seconds,
                      created_on, created_by_user_id, modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?,
                            ?, ?, ?,
                            ?, ?,
                            ?,
                            ?,
                            ?, ?, ?, ?,
                            ?, ?)
                    """)),
            entry(EntityType.START_TYPE, new Binding(
                    // SYSTEM_GLOBAL reference (StartTypeMapper): V2 seeds
                    // t_start_type by code; the legacy int StartTypes.StartTypeId
                    // resolves to a V2 code via StartTypeMapper's id→code table,
                    // and the bundle's legacy_id_map_START_TYPE (synthetic
                    // UUID(0, legacyId) → real t_start_type.id) lets a FLIGHT's
                    // start_type_id FK resolve. Only the int PK is projected — the
                    // legacy IsFor*Flights boolean trio is dropped (V2 owns the
                    // applicable_categories shape, ADR 0020).
                    PortPolicy.SYSTEM_GLOBAL,
                    "SELECT StartTypeId FROM StartTypes",
                    "t_start_type",
                    "")),
            entry(EntityType.FLIGHT_TYPE, new Binding(
                    // Tenant-scoped per-club reference (FlightTypeMapper): legacy
                    // FlightTypes.FlightTypeId → t_flight_type.id, ClubId →
                    // operating_club_id (the @TenantId per V3). NOT fan-out — one
                    // legacy row → one t_flight_type row, legacy_guid → id (like
                    // AIRCRAFT). Every projected column is a name the mapper's
                    // writeNdjson reads; IsCouponNumberRequired (v1.6),
                    // MinNrOfAircraftSeatsRequired (v1.8.10) and
                    // IsForAircraftReservationType (v1.9.23) are added by the
                    // DBUpdate chain and exist in the final FLSTest schema.
                    // IsSummarizedSystemFlight is intentionally NOT projected (no
                    // destination — the system-flight concept did not survive).
                    PortPolicy.FULL_PORT,
                    """
                    SELECT FlightTypeId, ClubId, FlightTypeName, FlightCode,
                           InstructorRequired, ObserverPilotOrInstructorRequired,
                           IsCheckFlight, IsPassengerFlight, IsSoloFlight,
                           IsForGliderFlights, IsForTowFlights, IsForMotorFlights,
                           IsFlightCostBalanceSelectable, IsCouponNumberRequired,
                           IsForAircraftReservationType, MinNrOfAircraftSeatsRequired,
                           CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                           DeletedOn, DeletedByUserId
                    FROM FlightTypes
                    """,
                    "t_flight_type",
                    // Non-fan-out FULL_PORT: legacy_guid → id. 22 params match
                    // FlightTypeMapper.columns() order.
                    """
                    INSERT INTO t_flight_type (
                      id, operating_club_id, flight_type_name, flight_code,
                      instructor_required, observer_pilot_or_instructor_required,
                      is_check_flight, is_passenger_flight, is_solo_flight,
                      is_for_glider_flights, is_for_tow_flights, is_for_motor_flights,
                      is_flight_cost_balance_selectable, is_coupon_number_required,
                      is_for_aircraft_reservation_type, min_nr_of_aircraft_seats_required,
                      created_on, created_by_user_id, modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?, ?,
                            ?, ?,
                            ?, ?, ?,
                            ?, ?, ?,
                            ?, ?,
                            ?, ?,
                            ?, ?, ?, ?,
                            ?, ?)
                    """)),
            entry(EntityType.FLIGHT, new Binding(
                    // Tenant-scoped aggregate root (FlightMapper): legacy
                    // Flights.FlightId → t_flight.id; operating_club_id is the
                    // @TenantId per V3, set per-flight from the real legacy OwnerId
                    // column (Flight.cs:130 — the ASP.NET ownership club, = the
                    // operating club per FlightReportService.cs:123 and the LOCATION
                    // fan-out key Flights.OwnerId AS ClubId). NOT OwnerClubId — no
                    // such column exists on Flights; the prior mapper read aborted
                    // the live export (J-1 T-16 class,
                    // project_synth_bundle_doesnt_validate_producer_select).
                    //
                    // Real legacy column reconciliation (V1.0 base + DBUpdate chain):
                    //  * StartType / FlightCostBalanceType — EF maps the C# props
                    //    StartTypeId / FlightCostBalanceTypeId via [Column(...)]; the
                    //    MSSQL columns are StartType / FlightCostBalanceType (read by
                    //    the mapper's getInt). Projected verbatim.
                    //  * FlightDate (v1.9.9), AirStateId / ProcessStateId /
                    //    ValidationErrors (v1.9.1), NrOfLdgsOnStartLocation /
                    //    NoStart/NoLdg TimeInformation (v1.9.0), CouponNumber
                    //    (renamed from PaxGiftCouponNumber, v1.6), NrOfPassengers
                    //    (v1.8.10), DeliveryCreatedOn (renamed from InvoicedOn,
                    //    v1.9.7), ValidatedOn (v1.4), FlightReportSentOn (v1.9.15),
                    //    Engine{Start,End}OperatingCounterInSeconds (v1.9.3) — all
                    //    exist in the final schema.
                    //  * AirStateId is projected only to drive the V13 lossy
                    //    translation (FlightPlanOpen(5) → ModifiedOn timestamp →
                    //    flight_plan_opened_on); it is NOT a destination column.
                    //  * ProcessStateId is projected twice-over: as the resolved
                    //    process_state_id reference FK AND to derive the net-new
                    //    locked_at (>= LOCKED(40) → ModifiedOn proxy).
                    //
                    // Tow self-FK (TowFlightId) is NOT in FlightMapper.foreignKeys()
                    // (declaring FLIGHT would break the ArchUnit ingest-order rule).
                    // FLIGHT is non-fan-out FULL_PORT, so legacy_guid is preserved as
                    // id and a real tow GUID == the migrated tow row's id (resolves
                    // for free); empty-guid (00000000-…) ports as null (oracle #18).
                    PortPolicy.FULL_PORT,
                    """
                    SELECT FlightId, OwnerId, AircraftId,
                           FlightDate, StartDateTime, LdgDateTime,
                           BlockStartDateTime, BlockEndDateTime,
                           StartLocationId, LdgLocationId,
                           StartRunway, LdgRunway, OutboundRoute, InboundRoute,
                           FlightTypeId, IsSoloFlight,
                           StartType, TowFlightId,
                           NrOfLdgs, NrOfLdgsOnStartLocation,
                           NoStartTimeInformation, NoLdgTimeInformation,
                           AirStateId, ProcessStateId, FlightAircraftType,
                           EngineStartOperatingCounterInSeconds,
                           EngineEndOperatingCounterInSeconds,
                           Comment, IncidentComment, ValidationErrors,
                           CouponNumber, FlightCostBalanceType,
                           DeliveryCreatedOn, ValidatedOn,
                           NrOfPassengers, StartPosition, FlightReportSentOn,
                           CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                           DeletedOn, DeletedByUserId
                    FROM Flights
                    """,
                    "t_flight",
                    // Non-fan-out FULL_PORT: legacy_guid → id. 44 params match
                    // FlightMapper.columns() order (V13 dropped air_state_id; V27
                    // appended locked_at last). The two-pass tow UPDATE the mapper
                    // Javadoc forward-references (S-141) is unnecessary while
                    // legacy_guid is preserved as id — tow_flight_id resolves to the
                    // tow row directly.
                    """
                    INSERT INTO t_flight (
                      id, operating_club_id, aircraft_id,
                      flight_date, start_date_time, ldg_date_time,
                      block_start_date_time, block_end_date_time,
                      start_location_id, ldg_location_id,
                      start_runway, ldg_runway, outbound_route, inbound_route,
                      flight_type_id, is_solo_flight,
                      start_type_id, tow_flight_id,
                      nr_of_ldgs, nr_of_ldgs_on_start_location,
                      no_start_time_information, no_ldg_time_information,
                      flight_plan_opened_on, process_state_id, flight_aircraft_type_id,
                      engine_start_operating_counter_in_seconds,
                      engine_end_operating_counter_in_seconds,
                      comment, incident_comment, validation_errors,
                      coupon_number, flight_cost_balance_type_id,
                      delivery_created_on, validated_on,
                      nr_of_passengers, start_position, flight_report_sent_on,
                      created_on, created_by_user_id, modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id,
                      locked_at)
                    VALUES (?, ?, ?,
                            ?, ?, ?,
                            ?, ?,
                            ?, ?,
                            ?, ?, ?, ?,
                            ?, ?,
                            ?, ?,
                            ?, ?,
                            ?, ?,
                            ?, ?, ?,
                            ?,
                            ?,
                            ?, ?, ?,
                            ?, ?,
                            ?, ?,
                            ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?,
                            ?)
                    """)),
            entry(EntityType.FLIGHT_CREW, new Binding(
                    // Aggregate-internal crew row under FLIGHT (FlightCrewMapper):
                    // legacy FlightCrew.FlightCrewId → t_flight_crew.id, FlightId →
                    // flight_id (resolves to the migrated flight), PersonId →
                    // person_id (cross-tenant Person, TENANT_BYPASS). FlightCrewType
                    // is the real int column (EF [Column("FlightCrewType")] over the
                    // C# FlightCrewTypeId) resolved to t_flight_crew_type via the
                    // legacy_int_id reference lookup. Non-fan-out: legacy_guid → id.
                    // V3 destination keeps only deleted_on/deleted_by_user_id (no
                    // created/modified audit pair on this child).
                    PortPolicy.FULL_PORT,
                    """
                    SELECT FlightCrewId, FlightId, PersonId, FlightCrewType,
                           BeginFlightDateTime, EndFlightDateTime,
                           BeginInstructionDateTime, EndInstructionDateTime,
                           NrOfLdgs, NrOfStarts,
                           DeletedOn, DeletedByUserId
                    FROM FlightCrew
                    """,
                    "t_flight_crew",
                    // 12 params match FlightCrewMapper.columns() order: id(legacy_guid),
                    // flight_id, person_id, flight_crew_type_id, the 4 datetimes,
                    // nr_of_ldgs/nr_of_starts, deleted_on/deleted_by_user_id.
                    """
                    INSERT INTO t_flight_crew (
                      id, flight_id, person_id, flight_crew_type_id,
                      begin_flight_datetime, end_flight_datetime,
                      begin_instruction_datetime, end_instruction_datetime,
                      nr_of_ldgs, nr_of_starts,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?, ?,
                            ?, ?,
                            ?, ?,
                            ?, ?,
                            ?, ?)
                    """)),
            entry(EntityType.AIRCRAFT_RESERVATION_TYPE, new Binding(
                    // Tenant-scoped per-club reference (AircraftReservationTypeMapper):
                    // legacy AircraftReservationTypes.AircraftReservationTypeId →
                    // t_aircraft_reservation_type.id, ClubId → operating_club_id (the
                    // @TenantId per V4). NOT fan-out — one legacy row → one row,
                    // legacy_guid → id (like FLIGHT_TYPE). The producer SELECT projects
                    // the POST-v1.9.23 schema: DBUpdate_v1.9.23 DROPs the v1.0
                    // AircraftReservationTypes table (3 columns) and re-creates it with
                    // the IsInstructorRequired / IsMaintenance / IsActive / ClubId /
                    // audit column set the mapper reads — so the names below are the
                    // re-created table's, not the v1.0 base names.
                    PortPolicy.FULL_PORT,
                    """
                    SELECT AircraftReservationTypeId, ClubId, AircraftReservationTypeName,
                           IsInstructorRequired, IsMaintenance, IsActive, Remarks,
                           CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                           DeletedOn, DeletedByUserId
                    FROM AircraftReservationTypes
                    """,
                    "t_aircraft_reservation_type",
                    // Non-fan-out FULL_PORT: legacy_guid → id. 13 params match
                    // AircraftReservationTypeMapper.columns() order.
                    """
                    INSERT INTO t_aircraft_reservation_type (
                      id, operating_club_id, reservation_type_name,
                      is_instructor_required, is_maintenance, is_active, remarks,
                      created_on, created_by_user_id, modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?)
                    """)),
            entry(EntityType.AIRCRAFT_RESERVATION, new Binding(
                    // Tenant-scoped aggregate root (AircraftReservationMapper): legacy
                    // AircraftReservations.AircraftReservationId → t_aircraft_reservation.id;
                    // operating_club_id is the @TenantId per V4, set from the real legacy
                    // ClubId column (added by DBUpdate_v1.1). NOT fan-out — one legacy row
                    // → one row, legacy_guid → id.
                    //
                    // Producer-SELECT reconciliation against the real post-v1.9.23 legacy
                    // schema (project_synth_bundle_doesnt_validate_producer_select):
                    //  * AircraftReservationTypeId — added v1.9.23, REPLACES the v1.0
                    //    ReservationTypeId (dropped in v1.9.23). The mapper reads the new
                    //    name; SELECTing the dropped ReservationTypeId would abort the
                    //    live export on a non-existent column.
                    //  * SecondCrewPersonId — added v1.9.23, REPLACES the v1.0
                    //    InstructorPersonId (also dropped in v1.9.23, after the data was
                    //    copied SecondCrewPersonId = InstructorPersonId).
                    //  * FlightTypeId — added v1.9.23 (nullable). LocationId / ClubId —
                    //    added v1.1. Start / End / IsAllDayReservation / PilotPersonId /
                    //    Remarks + audit columns are on the v1.0 base table.
                    //
                    // location_id + reservation_type_id are tenant-scoped FK targets
                    // resolved through the composite (legacy_guid, club_id) replica
                    // selection; aircraft_id / pilot_person_id / second_crew_person_id are
                    // cross-tenant (AIRCRAFT_RESERVATION is on the TENANT_BYPASS_ALLOW_LIST).
                    // reservation_range is GENERATED ALWAYS in V4 (derived from
                    // reservation_start + reservation_end) and so absent from both the
                    // mapper columns() and this INSERT.
                    PortPolicy.FULL_PORT,
                    """
                    SELECT AircraftReservationId, ClubId, AircraftId, Start, [End],
                           IsAllDayReservation, PilotPersonId, SecondCrewPersonId, LocationId,
                           AircraftReservationTypeId, FlightTypeId, Remarks,
                           CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                           DeletedOn, DeletedByUserId
                    FROM AircraftReservations
                    """,
                    "t_aircraft_reservation",
                    // Non-fan-out FULL_PORT: legacy_guid → id. 18 params match
                    // AircraftReservationMapper.columns() order. reservation_range
                    // (GENERATED ALWAYS, V4) is NOT bound.
                    """
                    INSERT INTO t_aircraft_reservation (
                      id, operating_club_id, aircraft_id,
                      reservation_start, reservation_end, is_all_day,
                      pilot_person_id, second_crew_person_id, location_id,
                      reservation_type_id, flight_type_id, info,
                      created_on, created_by_user_id, modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?,
                            ?, ?, ?,
                            ?, ?, ?,
                            ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?)
                    """)),
            entry(EntityType.PLANNING_DAY_ASSIGNMENT_TYPE, new Binding(
                    // Tenant-scoped per-club reference (PlanningDayAssignmentTypeMapper,
                    // J-6 T-11): legacy PlanningDayAssignmentTypes.PlanningDayAssignmentTypeId
                    // → t_planning_day_assignment_type.id, ClubId → operating_club_id (the
                    // @TenantId per V4). NOT fan-out — one legacy row → one row, legacy_guid
                    // → id (like FLIGHT_TYPE / AIRCRAFT_RESERVATION_TYPE).
                    //
                    // Producer-SELECT reconciliation against the real legacy schema
                    // (project_synth_bundle_doesnt_validate_producer_select): the mapper's
                    // writeNdjson reads getInt("RequiredNrOfAssignments"), but the real
                    // MSSQL column is RequiredNrOfPlanningDayAssignments (DBUpdate_v1.0.1
                    // DDL; the EF PlanningDayAssignmentType.cs property of that name, no
                    // [Column] rename). A SELECT naming the short form would abort the live
                    // export on a non-existent column — so it is projected AS
                    // RequiredNrOfAssignments (the name the mapper reads). The 3 well-known
                    // types are per-club rows in the real legacy data (NOT migration-seeded,
                    // oracle ClubService.cs:206-228) — they ride this stream verbatim.
                    PortPolicy.FULL_PORT,
                    """
                    SELECT PlanningDayAssignmentTypeId, ClubId, AssignmentTypeName,
                           RequiredNrOfPlanningDayAssignments AS RequiredNrOfAssignments,
                           CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                           DeletedOn, DeletedByUserId
                    FROM PlanningDayAssignmentTypes
                    """,
                    "t_planning_day_assignment_type",
                    // Non-fan-out FULL_PORT: legacy_guid → id. 10 params match
                    // PlanningDayAssignmentTypeMapper.columns() order.
                    """
                    INSERT INTO t_planning_day_assignment_type (
                      id, operating_club_id, assignment_type_name,
                      required_nr_of_assignments,
                      created_on, created_by_user_id, modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?,
                            ?,
                            ?, ?, ?, ?,
                            ?, ?)
                    """)),
            entry(EntityType.PLANNING_DAY, new Binding(
                    // Tenant-scoped planning-calendar aggregate root (PlanningDayMapper,
                    // J-6 T-11): legacy PlanningDays.PlanningDayId → t_planning_day.id;
                    // operating_club_id is the @TenantId per V4, set from the real legacy
                    // PlanningDays.ClubId column. NOT fan-out — one legacy row → one row,
                    // legacy_guid → id.
                    //
                    // Legacy column rename: Day → planning_date (the planned-for date, read
                    // verbatim via getDate("Day") — pure DATE, no tz shift on migrate).
                    // Remarks → info. Legacy ASP.NET artifacts (OwnerId/OwnershipType/
                    // RecordState/IsDeleted) are NOT projected — dropped per the mapper.
                    //
                    // location_id → tenant-scoped LOCATION, which FANS OUT (one replica per
                    // referencing club). PlanningDay carries no own club_id wire field, so
                    // PlanningDayMapper.foreignKeyColumns() names operating_club_id as the
                    // fan-out disambiguator: the (legacy_guid, club_id) composite resolve
                    // lands on the day's OWN-club Location replica (the AircraftReservation
                    // idiom, J-5). The (operating_club_id, planning_date, location_id) UNIQUE
                    // partial (V4 ux_pln_club_date_loc) corrects legacy's no-dedup bug.
                    //
                    // PRODUCER-SIDE DEDUPE-KEEP-FIRST (J-6 T-11b): the real legacy
                    // PlanningDays table carries DUPLICATE (ClubId, Day, LocationId) rows
                    // (no legacy UNIQUE constraint). PLANNING_DAY is NOT fan-out, so two
                    // dup legacy rows resolve to the SAME provisioned club + the SAME
                    // own-club Location replica — the 2nd INSERT then violates V4's
                    // ux_pln_club_date_loc and the ingest fail-closes with sqlstate 23505
                    // (the §4 fanout gate blocker). The mapper's Javadoc promises a
                    // producer-side dedupe-keep-first BEFORE any row reaches it; this is
                    // that dedupe. ROW_NUMBER() OVER (PARTITION BY the UNIQUE-partial key,
                    // ORDER BY CreatedOn, PlanningDayId) keeps the deterministically-first
                    // row per (ClubId, Day, LocationId) and drops the rest. Day is a pure
                    // DATE, so two rows on the same day at different creation times still
                    // collide on the V4 partial — partition on Day, matching the schema.
                    // The dropped dups carry a PLANNING_DAY_DUPLICATE drop-code
                    // (ProducerDropReconciliation, ROW_DROP_CODES) — the reconciliation
                    // SURFACING at the parity gate is deferred to S-187a (ParityDiffEngine
                    // resolves only CLUB/USER today), so the drop is currently SILENT at the
                    // gate; the dedupe correctness is locked by PlanningDayProducerDedupeIT,
                    // and it drops nothing the V4 partial UNIQUE would have kept as live.
                    // ROW_NUMBER() OVER is T-SQL native (the live legacy MSSQL dialect).
                    PortPolicy.FULL_PORT,
                    """
                    SELECT PlanningDayId, ClubId, Day, LocationId, Remarks,
                           CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                           DeletedOn, DeletedByUserId
                    FROM (
                        SELECT PlanningDayId, ClubId, Day, LocationId, Remarks,
                               CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                               DeletedOn, DeletedByUserId,
                               ROW_NUMBER() OVER (
                                   PARTITION BY ClubId, Day, LocationId
                                   ORDER BY CreatedOn, PlanningDayId
                               ) AS rn
                        FROM PlanningDays
                    ) deduped
                    WHERE rn = 1
                    """,
                    "t_planning_day",
                    // Non-fan-out FULL_PORT: legacy_guid → id. 11 params match
                    // PlanningDayMapper.columns() order.
                    """
                    INSERT INTO t_planning_day (
                      id, operating_club_id, planning_date, location_id, info,
                      created_on, created_by_user_id, modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?)
                    """)),
            entry(EntityType.PLANNING_DAY_ASSIGNMENT, new Binding(
                    // Tenant-scoped planning role assignment (PlanningDayAssignmentMapper,
                    // J-6 T-11): legacy PlanningDayAssignments.PlanningDayAssignmentId →
                    // t_planning_day_assignment.id. NOT fan-out — legacy_guid → id.
                    //
                    // Producer-side JOIN (the real legacy PlanningDayAssignments table has
                    // NO own ClubId column — DBUpdate_v1.0.1 DDL): operating_club_id (the
                    // denormalised @TenantId per V4) is sourced by JOINing the parent
                    // PlanningDays and projecting its ClubId AS OperatingClubId, the name
                    // the mapper's writeNdjson reads. assigned_person_id → cross-tenant
                    // PERSON (Manifest TENANT_BYPASS_ALLOW_LIST); planning_day_id →
                    // PLANNING_DAY; assignment_type_id → PLANNING_DAY_ASSIGNMENT_TYPE — all
                    // resolved through their own id-maps. Remarks → info. Legacy ASP.NET
                    // artifacts dropped.
                    //
                    // PARENT-DEDUPE REMAP (J-6 T-16, the 23503 fix): the PLANNING_DAY
                    // producer above keeps-first per (ClubId, Day, LocationId) and DROPS the
                    // duplicate legacy days, so a dropped day's GUID never lands in
                    // t_planning_day. The real legacy FLSTest fixture has TWO planning days
                    // ('Test'/'Test2') on the SAME (Club, GETDATE()+1, LSZK) — one is
                    // dropped — yet BOTH carry assignments. Exporting an assignment whose
                    // AssignedPlanningDayId is the dropped day would INSERT a
                    // planning_day_id that has no parent row → fk_pda_planning_day_id
                    // violation (sqlstate 23503, the masked INGEST_INTERNAL_ERROR the §4
                    // fanout surfaced once 23505 cleared). So we REMAP each assignment's
                    // planning_day_id onto the SURVIVING (kept-first) day for its parent's
                    // (ClubId, Day, LocationId) — the same ROW_NUMBER() keep-first the
                    // PLANNING_DAY SELECT uses — so every exported assignment points at a
                    // day that WILL exist post-dedupe. Semantically lossless: the survivor
                    // IS that (club, date, location) planning day; the dropped duplicate's
                    // crew simply attaches to the one kept day.
                    //
                    // POST-REMAP COMPOSITE DEDUPE (J-7 T-17, the 23505 fix): the remap
                    // above can COLLAPSE two assignments onto the SAME planning_day_id. The
                    // real legacy FLSTest fixture has TWO planning days ('Test'/'Test2') on
                    // the SAME (Club, Day, LSZK), BOTH carrying assignments — and when both
                    // days' assignments share the SAME (AssignedPersonId, AssignmentTypeId),
                    // the remap re-points BOTH at the one surviving day → two rows with an
                    // identical (planning_day_id, assigned_person_id, assignment_type_id) →
                    // V4's ux_pda_composite UNIQUE 23505 at ingest (the §4 fanout blocker the
                    // backend.log dig diagnosed). The J-6 remap fixed the 23503 FK but
                    // introduced this composite collision. So we DEDUPE-KEEP-FIRST on the
                    // POST-REMAP composite: ROW_NUMBER() OVER (PARTITION BY KeptPlanningDayId,
                    // AssignedPersonId, AssignmentTypeId ORDER BY <a live/non-deleted row
                    // first, then earliest CreatedOn, then the GUID as a deterministic
                    // tiebreak>) keeps exactly one row per post-remap composite and drops the
                    // rest. The DeletedOn-IS-NULL-first ordering heeds the J-6 gap-hunter
                    // rider "producer dedupe is soft-delete-blind": when a live and a
                    // soft-deleted assignment collide on the composite, the LIVE one must win
                    // (a soft-deleted assignment surviving over a live one would silently drop
                    // a real crew assignment). The dropped dups carry a
                    // PLANNING_DAY_ASSIGNMENT_DUPLICATE drop-code (ProducerDropReconciliation,
                    // ROW_DROP_CODES) mirroring PLANNING_DAY_DUPLICATE — but, like that one, the
                    // gate SURFACING is deferred to S-187a (silent at the gate today); the dedupe
                    // is locked by PlanningDayProducerDedupeIT. ROW_NUMBER() OVER + the
                    // CASE expression are T-SQL native (the live legacy MSSQL dialect) and
                    // evaluate identically on the Postgres test container.
                    //
                    // ux_pda_composite is a PARTIAL unique (WHERE deleted_on IS NULL, V4:339):
                    // only LIVE rows actually collide at ingest. The dedupe partitions on the
                    // composite regardless of deleted_on (keeping one row per composite) — a
                    // touch over-aggressive (two soft-deleted dups would not collide on the
                    // partial index) but harmless for migration fidelity, and the live-first
                    // ORDER BY guarantees a LIVE row is never dropped in favour of a deleted
                    // one. Simpler than a deleted_on-aware partition, with no fidelity loss.
                    PortPolicy.FULL_PORT,
                    """
                    SELECT PlanningDayAssignmentId,
                           OperatingClubId,
                           AssignedPlanningDayId,
                           AssignedPersonId,
                           AssignmentTypeId, Remarks,
                           CreatedOn, CreatedByUserId,
                           ModifiedOn, ModifiedByUserId,
                           DeletedOn, DeletedByUserId
                    FROM (
                        SELECT pda.PlanningDayAssignmentId,
                               pd.ClubId AS OperatingClubId,
                               kept.KeptPlanningDayId AS AssignedPlanningDayId,
                               pda.AssignedPersonId,
                               pda.AssignmentTypeId, pda.Remarks,
                               pda.CreatedOn, pda.CreatedByUserId,
                               pda.ModifiedOn, pda.ModifiedByUserId,
                               pda.DeletedOn, pda.DeletedByUserId,
                               ROW_NUMBER() OVER (
                                   PARTITION BY kept.KeptPlanningDayId,
                                                pda.AssignedPersonId,
                                                pda.AssignmentTypeId
                                   ORDER BY CASE WHEN pda.DeletedOn IS NULL THEN 0 ELSE 1 END,
                                            pda.CreatedOn,
                                            pda.PlanningDayAssignmentId
                               ) AS composite_rn
                        FROM PlanningDayAssignments pda
                        JOIN PlanningDays pd ON pd.PlanningDayId = pda.AssignedPlanningDayId
                        JOIN (
                            SELECT ClubId, Day, LocationId,
                                   FIRST_VALUE(PlanningDayId) OVER (
                                       PARTITION BY ClubId, Day, LocationId
                                       ORDER BY CreatedOn, PlanningDayId
                                   ) AS KeptPlanningDayId,
                                   PlanningDayId
                            FROM PlanningDays
                        ) kept ON kept.PlanningDayId = pda.AssignedPlanningDayId
                    ) remapped
                    WHERE composite_rn = 1
                    """,
                    "t_planning_day_assignment",
                    // Non-fan-out FULL_PORT: legacy_guid → id. 12 params match
                    // PlanningDayAssignmentMapper.columns() order.
                    """
                    INSERT INTO t_planning_day_assignment (
                      id, operating_club_id, planning_day_id,
                      assigned_person_id, assignment_type_id, info,
                      created_on, created_by_user_id, modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?,
                            ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?)
                    """)),
            entry(EntityType.ACCOUNTING_RULE_FILTER, new Binding(
                    // Tenant-scoped accounting-rule aggregate root
                    // (AccountingRuleFilterMapper, J-8 T-10): legacy
                    // AccountingRuleFilters.AccountingRuleFilterId →
                    // t_accounting_rule_filter.id; operating_club_id is the @TenantId
                    // per V4, set from the real legacy ClubId column. NOT fan-out —
                    // one legacy row → one row, legacy_guid → id (like FLIGHT_TYPE).
                    //
                    // filter_type_id + accounting_unit_type_id are emitted by the
                    // mapper as the synthetic new UUID(0, legacyIntId) and resolved
                    // ingest-side via the mapper's referenceLookups() against the
                    // V4/V42-seeded t_accounting_rule_filter_type / t_accounting_unit_type
                    // legacy_int_id (NOT bundle EntityType FKs) — so the SELECT projects
                    // the raw legacy int columns AccountingRuleFilterTypeId /
                    // AccountingUnitTypeId verbatim.
                    //
                    // TARGET JSON-BLOB EXTRACTION (project_synth_bundle_doesnt_validate
                    // _producer_select): the legacy ArticleTarget / RecipientTarget are
                    // nvarchar(max) JSON blobs (JsonConvert.SerializeObject of
                    // ArticleTargetDetails {ArticleNumber, DeliveryLineText} /
                    // RecipientDetails {PersonClubMemberNumber, RecipientName, …} —
                    // AccountingRuleFilterMappingExtensions.cs:75/256/347/359). The new
                    // article_target / recipient_target are VARCHAR(50) scalars, so the
                    // producer extracts the bare scalar via T-SQL JSON_VALUE aliased to
                    // the name the mapper reads; the descriptive DeliveryLineText /
                    // RecipientName ride filter_config (read by buildFilterConfig).
                    // Dumping the whole blob into VARCHAR(50) would overflow.
                    //
                    // The Matched* columns are JSON arrays (the mapper parses them as
                    // such) and are projected verbatim — the mapper's
                    // appendJsonArrayElements does the parse, not the SELECT.
                    //
                    // SORT-INDICATOR RENUMBER ON COLLISION (ux_arf_club_sort_partial,
                    // J-8 T-10): V4's partial UNIQUE (operating_club_id, sort_indicator)
                    // WHERE deleted_on IS NULL forbids per-club sort-indicator dups, but
                    // legacy SortIndicator is int NULL + dup-prone (no such legacy
                    // constraint). A real club with two rows sharing a SortIndicator (or
                    // NULL) would 23505 at the 2nd INSERT — the §4 fanout blocker. So the
                    // producer RENUMBERS: ROW_NUMBER() OVER (PARTITION BY ClubId ORDER BY
                    // SortIndicator, CreatedOn, AccountingRuleFilterId) assigns each LIVE
                    // row a dense, distinct 1..N per club (ACCOUNTING_RULE_SORT_RENUMBERED).
                    // The CASE … SortIndicator IS NULL THEN 0 ELSE 1 lead-key forces NULL
                    // indicators FIRST identically on BOTH dialects (T-SQL ASC is NULLS
                    // FIRST, Postgres ASC is NULLS LAST — the explicit CASE makes the
                    // ordering dialect-portable so the test container and the live MSSQL
                    // agree); ORDER BY SortIndicator then preserves the legacy intended
                    // order, CreatedOn + the GUID are deterministic tiebreaks. The renumber
                    // partitions ONLY over the rows the partial index covers (WHERE
                    // IsDeleted = 0, the J-6 soft-delete-blind dedupe rider) so a
                    // soft-deleted row never consumes a live row's index nor collides.
                    // Soft-deleted rows are dropped (legacy IsDeleted = 1 is the new
                    // deleted_on convention; the other tenant mappers carry deleted_on
                    // but legacy AccountingRuleFilters has BOTH IsDeleted and DeletedOn,
                    // and the partial UNIQUE only covers deleted_on IS NULL — exporting a
                    // soft-deleted row with a renumbered live index would be wrong, so we
                    // filter them out entirely, consistent with the partial-index
                    // semantics). Locked by AccountingRuleFilterProducerDedupeIT.
                    // ROW_NUMBER() OVER + JSON_VALUE are T-SQL native (the live legacy
                    // MSSQL dialect) and evaluate identically on the Postgres test
                    // container.
                    PortPolicy.FULL_PORT,
                    """
                    SELECT AccountingRuleFilterId, ClubId,
                           AccountingRuleFilterTypeId, AccountingUnitTypeId,
                           RuleFilterName, Description, IsActive,
                           ROW_NUMBER() OVER (
                               PARTITION BY ClubId
                               ORDER BY CASE WHEN SortIndicator IS NULL THEN 0 ELSE 1 END,
                                        SortIndicator, CreatedOn, AccountingRuleFilterId
                           ) AS SortIndicator,
                           StopRuleEngineWhenRuleApplied, IsChargedToClubInternal,
                           JSON_VALUE(ArticleTarget, '$.ArticleNumber') AS ArticleTarget,
                           JSON_VALUE(ArticleTarget, '$.DeliveryLineText') AS DeliveryLineText,
                           JSON_VALUE(RecipientTarget, '$.PersonClubMemberNumber')
                               AS RecipientTarget,
                           JSON_VALUE(RecipientTarget, '$.RecipientName') AS RecipientName,
                           IsRuleForGliderFlights, IsRuleForTowingFlights,
                           IsRuleForMotorFlights,
                           NoLandingTaxForGlider, NoLandingTaxForTowingAircraft,
                           NoLandingTaxForAircraft,
                           IncludeFlightTypeName,
                           ExtendMatchingFlightTypeCodesToGliderAndTowFlight,
                           IncludeThresholdText, ThresholdText,
                           MinFlightTimeInSecondsMatchingValue,
                           MaxFlightTimeInSecondsMatchingValue,
                           MinEngineTimeInSecondsMatchingValue,
                           MaxEngineTimeInSecondsMatchingValue,
                           UseRuleForAllAircraftsExceptListed,
                           MatchedAircraftImmatriculations,
                           UseRuleForAllStartTypesExceptListed, MatchedStartTypes,
                           UseRuleForAllFlightTypesExceptListed, MatchedFlightTypeCodes,
                           UseRuleForAllStartLocationsExceptListed, MatchedStartLocations,
                           UseRuleForAllLdgLocationsExceptListed, MatchedLdgLocations,
                           UseRuleForAllClubMemberNumbersExceptListed,
                           MatchedClubMemberNumbers,
                           UseRuleForAllFlightCrewTypesExceptListed, MatchedFlightCrewTypes,
                           UseRuleForAllAircraftsOnHomebaseExceptListed,
                           MatchedAircraftsHomebase,
                           UseRuleForAllMemberStatesExceptListed, MatchedMemberStates,
                           UseRuleForAllPersonCategoriesExceptListed,
                           MatchedPersonCategories,
                           CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                           DeletedOn, DeletedByUserId
                    FROM AccountingRuleFilters
                    WHERE IsDeleted = 0
                    """,
                    "t_accounting_rule_filter",
                    // Non-fan-out FULL_PORT: legacy_guid → id. 19 params match
                    // AccountingRuleFilterMapper.columns() order.
                    """
                    INSERT INTO t_accounting_rule_filter (
                      id, operating_club_id, filter_type_id, accounting_unit_type_id,
                      rule_filter_name, description, is_active, sort_indicator,
                      stop_rule_engine_when_applied, is_charged_to_club_internal,
                      article_target, recipient_target, filter_config,
                      created_on, created_by_user_id, modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?,
                            ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?)
                    """)),
            entry(EntityType.ARTICLE, new Binding(
                    // Tenant-scoped catalog aggregate (ArticleMapper): legacy
                    // Articles.ArticleId → t_article.id; operating_club_id is the @TenantId
                    // per V3, from the real legacy ClubId. NOT fan-out — one legacy row →
                    // one row, legacy_guid → id. The DeliveryItem producer resolves
                    // article_id by (ClubId, ArticleNumber) against this stream's
                    // legacy_id_map_article, so DELIVERY_ITEM cannot migrate without ARTICLE.
                    //
                    // Soft-deleted rows (IsDeleted = 1) are dropped — ux_article_club_number
                    // is partial (deleted_on IS NULL) and the mapper hard-fails (never
                    // silently dedupes) a live (operating_club_id, article_number) collision,
                    // so exporting a soft-deleted row with a live number would 23505 a real
                    // article. Legacy ASP.NET artifacts dropped: OwnerId, OwnershipType,
                    // RecordState, IsDeleted.
                    PortPolicy.FULL_PORT,
                    """
                    SELECT ArticleId, ClubId, ArticleNumber, ArticleName, ArticleInfo,
                           Description, IsActive,
                           CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                           DeletedOn, DeletedByUserId
                    FROM Articles
                    WHERE IsDeleted = 0
                    """,
                    "t_article",
                    // Non-fan-out FULL_PORT: legacy_guid → id. 13 params match
                    // ArticleMapper.columns() order.
                    """
                    INSERT INTO t_article (
                      id, operating_club_id, article_number, article_name, article_info,
                      description, is_active,
                      created_on, created_by_user_id, modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?, ?, ?,
                            ?, ?,
                            ?, ?, ?, ?,
                            ?, ?)
                    """)),
            entry(EntityType.DELIVERY, new Binding(
                    // Tenant-scoped invoice-delivery aggregate root (DeliveryMapper):
                    // legacy Deliveries.DeliveryId → t_delivery.id; operating_club_id is
                    // the @TenantId per V4, set from the real legacy ClubId. NOT fan-out —
                    // one legacy row → one row, legacy_guid → id (like FLIGHT_TYPE).
                    //
                    // STATE PROMOTION (project_synth_bundle_doesnt_validate_producer_select):
                    // legacy Deliveries has NO ProcessStateId — state lives on
                    // Flights.ProcessStateId + Deliveries.IsFurtherProcessed. The V4 schema
                    // promotes it to first-class on Delivery, so the producer LEFT JOINs the
                    // optional Flight and derives the SMALLINT enum the mapper reads as
                    // ResolvedProcessStateId: IsFurtherProcessed wins → 20 (Booked); else
                    // Flights.ProcessStateId 60 → 20, 50 → 10, 45 → 30; everything else (incl.
                    // a Delivery with no Flight) → 10 (Prepared). A CASE expression, dialect-
                    // portable across the live MSSQL producer + the PG test container.
                    //
                    // DELIVERY-NUMBER PARSE (V19 reshape): legacy DeliveryNumber is operator-
                    // formatted NVARCHAR; V4 reshapes to INTEGER + a legacy_delivery_number_text
                    // parity column. TRY_CONVERT(INT, …) (T-SQL native; the PG test container
                    // gets a CAST-on-all-digits shim in the IT staging table) yields the integer
                    // into ResolvedDeliveryNumber on parse success (text NULL), or NULL +
                    // the raw text into ResolvedLegacyDeliveryNumberText on parse failure.
                    // NO dedupe on a parse-collision: two distinct texts parsing to the SAME
                    // integer for one club surface as ux_dlv_club_number_partial 23505 at
                    // ingest — silently renumbering would corrupt the Swiss OR Art. 957a
                    // legal-record invoice number (the ArticleMapper hard-fail idiom). Locked
                    // by DeliveryProducerCollisionOrphanIT.
                    //
                    // recipient_person_id → cross-tenant PERSON (FK ON DELETE SET NULL, V4).
                    // The resolver leaves a FULL_PORT FK verbatim on a miss → an unmigrated
                    // recipient would 23503 at INSERT (SET NULL fires only on DELETE). So the
                    // producer LEFT JOINs Persons and emits NULL RecipientPersonId when the
                    // recipient is not in the export — the frozen recipient_* snapshot (9
                    // scalar passthroughs) still renders. flight_id → same-tenant FLIGHT
                    // (RESTRICT): an unmigrated flight is a real integrity break and 23503s.
                    //
                    // Legacy ASP.NET artifacts dropped: OwnerId, OwnershipType, RecordState,
                    // IsDeleted, IsFurtherProcessed (collapsed into ResolvedProcessStateId).
                    PortPolicy.FULL_PORT,
                    """
                    SELECT d.DeliveryId, d.ClubId,
                           CASE
                               WHEN d.IsFurtherProcessed = 1 THEN 20
                               WHEN f.ProcessStateId = 60 THEN 20
                               WHEN f.ProcessStateId = 50 THEN 10
                               WHEN f.ProcessStateId = 45 THEN 30
                               ELSE 10
                           END AS ResolvedProcessStateId,
                           d.FlightId,
                           p.PersonId AS RecipientPersonId,
                           d.RecipientName, d.RecipientFirstname, d.RecipientLastname,
                           d.RecipientAddressLine1, d.RecipientAddressLine2,
                           d.RecipientZipCode, d.RecipientCity, d.RecipientCountryName,
                           d.RecipientPersonClubMemberNumber,
                           d.DeliveryInformation, d.AdditionalInformation,
                           TRY_CONVERT(INT, d.DeliveryNumber) AS ResolvedDeliveryNumber,
                           CASE WHEN TRY_CONVERT(INT, d.DeliveryNumber) IS NULL
                                THEN d.DeliveryNumber END AS ResolvedLegacyDeliveryNumberText,
                           d.DeliveredOn, d.BatchId,
                           d.CreatedOn, d.CreatedByUserId, d.ModifiedOn, d.ModifiedByUserId,
                           d.DeletedOn, d.DeletedByUserId
                    FROM Deliveries d
                    LEFT JOIN Flights f ON f.FlightId = d.FlightId
                    LEFT JOIN Persons p ON p.PersonId = d.RecipientPersonId
                    """,
                    "t_delivery",
                    // Non-fan-out FULL_PORT: legacy_guid → id. 26 params match
                    // DeliveryMapper.columns() order.
                    """
                    INSERT INTO t_delivery (
                      id, operating_club_id, process_state_id,
                      flight_id, recipient_person_id,
                      recipient_name, recipient_firstname, recipient_lastname,
                      recipient_address_line1, recipient_address_line2,
                      recipient_zip_code, recipient_city, recipient_country_name,
                      recipient_person_club_member_number,
                      delivery_information, additional_information,
                      delivery_number, legacy_delivery_number_text,
                      delivered_on, batch_id,
                      created_on, created_by_user_id, modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?,
                            ?, ?,
                            ?, ?, ?,
                            ?, ?,
                            ?, ?, ?,
                            ?,
                            ?, ?,
                            ?, ?,
                            ?, ?,
                            ?, ?, ?, ?,
                            ?, ?)
                    """)),
            entry(EntityType.DELIVERY_ITEM, new Binding(
                    // Tenant-scoped delivery line item (DeliveryItemMapper): legacy
                    // DeliveryItems.DeliveryItemId → t_delivery_item.id. NOT fan-out —
                    // legacy_guid → id. Legacy DeliveryItems has NO ClubId, ArticleId, or
                    // UnitPrice; all are denormalised/resolved producer-side
                    // (project_synth_bundle_doesnt_validate_producer_select):
                    //
                    //  * OperatingClubId — denormalised from the parent Delivery's ClubId
                    //    (INNER JOIN Deliveries; the item's club IS its delivery's club, the
                    //    V4 invariant). INNER, not LEFT: a DeliveryItem with no parent
                    //    Delivery is malformed legacy data, dropped by the join.
                    //  * ResolvedArticleId — the legacy Article GUID resolved by
                    //    (ClubId, ArticleNumber): the mapper declares ARTICLE in foreignKeys(),
                    //    so the FK rewriter maps this legacy GUID through legacy_id_map_article.
                    //    LEFT JOIN Articles emits the legacy ArticleId; an item whose
                    //    (club, number) has no migrated Article leaves the GUID unresolved
                    //    → the article_id RESTRICT FK 23503s at INSERT (the orphan is a real
                    //    invoice-integrity break, never silently dropped — Swiss OR Art. 957a).
                    //  * ResolvedUnitPrice — legacy has no price column; V4 unit_price defaults
                    //    0. The read screen is display-only this iteration; emit the 0 default
                    //    (the write-side back-fill from the article master is J-10b/S-016).
                    //
                    // Legacy ASP.NET artifacts dropped: OwnerId, OwnershipType, RecordState,
                    // IsDeleted.
                    PortPolicy.FULL_PORT,
                    """
                    SELECT di.DeliveryItemId, d.ClubId AS OperatingClubId, di.DeliveryId,
                           di.Position,
                           a.ArticleId AS ResolvedArticleId, di.ArticleNumber,
                           di.ItemText, di.AdditionalInformation,
                           di.Quantity,
                           CAST(0 AS NUMERIC(12, 4)) AS ResolvedUnitPrice,
                           di.DiscountInPercent, di.UnitType,
                           di.CreatedOn, di.CreatedByUserId,
                           di.ModifiedOn, di.ModifiedByUserId,
                           di.DeletedOn, di.DeletedByUserId
                    FROM DeliveryItems di
                    JOIN Deliveries d ON d.DeliveryId = di.DeliveryId
                    LEFT JOIN Articles a
                        ON a.ClubId = d.ClubId AND a.ArticleNumber = di.ArticleNumber
                    """,
                    "t_delivery_item",
                    // Non-fan-out FULL_PORT: legacy_guid → id. 18 params match
                    // DeliveryItemMapper.columns() order.
                    """
                    INSERT INTO t_delivery_item (
                      id, operating_club_id, delivery_id, position,
                      article_id, article_number, item_text, additional_information,
                      quantity, unit_price, discount_in_percent, unit_type_code,
                      created_on, created_by_user_id,
                      modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?,
                            ?, ?,
                            ?, ?)
                    """)));

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
