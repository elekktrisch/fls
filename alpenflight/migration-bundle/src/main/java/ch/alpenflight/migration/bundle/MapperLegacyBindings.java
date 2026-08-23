package ch.alpenflight.migration.bundle;

import static java.util.Map.entry;

import java.util.Map;

public final class MapperLegacyBindings {

    public enum PortPolicy { FULL_PORT, SYSTEM_GLOBAL }

    public record Binding(
            PortPolicy portPolicy,
            String legacySelect,
            String newSchemaTable,
            String newSchemaInsert) {
    }

    private static final String LEGACY_ASPNET_SYSTEM_USER_ID_NEVER_MIGRATED =
            "13731EE2-C1D8-455C-8AD1-C39399893FFF";

    private static final String PLANNING_DAY_SURVIVOR_ORDER_LIVE_ROW_BEATS_SOFT_DELETED =
            "ORDER BY IsDeleted, CreatedOn, PlanningDayId";

    private static final String LEGACY_AUDIT_ENTITY_NAMESPACE_STRIPPED_BY_THE_LEGACY_READ_PATH =
            "FLS.Server.Data.DbEntities.";

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
                    """
                    SELECT UserId, ClubId, UserName, FriendlyName, PersonId,
                           NotificationEmail, PhoneNumber, Remarks, LanguageId,
                           CreatedOn, CreatedByUserId,
                           ModifiedOn, ModifiedByUserId, DeletedOn, DeletedByUserId
                    FROM Users
                    WHERE UserId <> '%s'
                    """.formatted(LEGACY_ASPNET_SYSTEM_USER_ID_NEVER_MIGRATED),
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
                    """
                    SELECT AircraftId, AircraftState,
                           ValidFrom, ValidTo, NoticedByPersonId, Remarks,
                           CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                           DeletedOn, DeletedByUserId
                    FROM AircraftAircraftStates
                    """,
                    "t_aircraft_aircraft_state",
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
                    PortPolicy.SYSTEM_GLOBAL,
                    "SELECT StartTypeId FROM StartTypes",
                    "t_start_type",
                    "")),
            entry(EntityType.FLIGHT_TYPE, new Binding(
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
                    PortPolicy.FULL_PORT,
                    """
                    SELECT AircraftReservationTypeId, ClubId, AircraftReservationTypeName,
                           IsInstructorRequired, IsMaintenance, IsActive, Remarks,
                           CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                           DeletedOn, DeletedByUserId
                    FROM AircraftReservationTypes
                    """,
                    "t_aircraft_reservation_type",
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
                    PortPolicy.FULL_PORT,
                    """
                    SELECT PlanningDayAssignmentTypeId, ClubId, AssignmentTypeName,
                           RequiredNrOfPlanningDayAssignments AS RequiredNrOfAssignments,
                           CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                           DeletedOn, DeletedByUserId
                    FROM PlanningDayAssignmentTypes
                    """,
                    "t_planning_day_assignment_type",
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
                                   %s
                               ) AS rn
                        FROM PlanningDays
                    ) deduped
                    WHERE rn = 1
                    """.formatted(PLANNING_DAY_SURVIVOR_ORDER_LIVE_ROW_BEATS_SOFT_DELETED),
                    "t_planning_day",
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
                                   ORDER BY pda.IsDeleted,
                                            pda.CreatedOn,
                                            pda.PlanningDayAssignmentId
                               ) AS composite_rn
                        FROM PlanningDayAssignments pda
                        JOIN PlanningDays pd ON pd.PlanningDayId = pda.AssignedPlanningDayId
                        JOIN (
                            SELECT ClubId, Day, LocationId,
                                   FIRST_VALUE(PlanningDayId) OVER (
                                       PARTITION BY ClubId, Day, LocationId
                                       %s
                                   ) AS KeptPlanningDayId,
                                   PlanningDayId
                            FROM PlanningDays
                        ) kept ON kept.PlanningDayId = pda.AssignedPlanningDayId
                    ) remapped
                    WHERE composite_rn = 1
                    """.formatted(PLANNING_DAY_SURVIVOR_ORDER_LIVE_ROW_BEATS_SOFT_DELETED),
                    "t_planning_day_assignment",
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
            entry(EntityType.ARTICLE, new Binding(
                    PortPolicy.FULL_PORT,
                    """
                    SELECT ArticleId, ClubId, ArticleNumber, ArticleName,
                           ArticleInfo, Description, IsActive,
                           CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                           DeletedOn, DeletedByUserId
                    FROM Articles
                    """,
                    "t_article",
                    """
                    INSERT INTO t_article (
                      id, operating_club_id, article_number, article_name,
                      article_info, description, is_active,
                      created_on, created_by_user_id, modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?, ?,
                            ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?)
                    """)),
            entry(EntityType.ACCOUNTING_RULE_FILTER, new Binding(
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
                           CASE WHEN LEFT(LTRIM(ArticleTarget), 1) IN ('{', '[')
                                THEN JSON_VALUE(ArticleTarget, '$.ArticleNumber') END
                               AS ArticleTarget,
                           CASE WHEN LEFT(LTRIM(ArticleTarget), 1) IN ('{', '[')
                                THEN JSON_VALUE(ArticleTarget, '$.DeliveryLineText') END
                               AS DeliveryLineText,
                           CASE WHEN LEFT(LTRIM(RecipientTarget), 1) IN ('{', '[')
                                THEN JSON_VALUE(RecipientTarget, '$.PersonClubMemberNumber') END
                               AS RecipientTarget,
                           CASE WHEN LEFT(LTRIM(RecipientTarget), 1) IN ('{', '[')
                                THEN JSON_VALUE(RecipientTarget, '$.RecipientName') END
                               AS RecipientName,
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
            entry(EntityType.DELIVERY, new Binding(
                    PortPolicy.FULL_PORT,
                    """
                    SELECT d.DeliveryId, d.ClubId,
                           CASE WHEN d.IsFurtherProcessed = 1 THEN 20
                                WHEN f.ProcessStateId = 60 THEN 20
                                WHEN f.ProcessStateId = 50 THEN 10
                                WHEN f.ProcessStateId = 45 THEN 30
                                ELSE 10 END AS ResolvedProcessStateId,
                           d.FlightId, d.RecipientPersonId,
                           d.RecipientName, d.RecipientFirstname, d.RecipientLastname,
                           d.RecipientAddressLine1, d.RecipientAddressLine2,
                           d.RecipientZipCode, d.RecipientCity, d.RecipientCountryName,
                           d.RecipientPersonClubMemberNumber,
                           d.DeliveryInformation, d.AdditionalInformation,
                           d.DeliveryNumber, d.DeliveredOn,
                           COALESCE(d.BatchId, 0) AS BatchId,
                           d.CreatedOn, d.CreatedByUserId, d.ModifiedOn, d.ModifiedByUserId,
                           d.DeletedOn, d.DeletedByUserId
                    FROM Deliveries d
                    LEFT JOIN Flights f ON f.FlightId = d.FlightId
                    """,
                    "t_delivery",
                    """
                    INSERT INTO t_delivery (
                      id, operating_club_id, process_state_id,
                      flight_id, recipient_person_id,
                      recipient_name, recipient_firstname, recipient_lastname,
                      recipient_address_line1, recipient_address_line2,
                      recipient_zip_code, recipient_city, recipient_country_name,
                      recipient_person_club_member_number,
                      delivery_information, additional_information,
                      delivery_number, delivered_on, batch_id,
                      created_on, created_by_user_id, modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?,
                            ?, ?,
                            ?, ?, ?,
                            ?, ?,
                            ?, ?, ?,
                            ?,
                            ?, ?,
                            ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?)
                    """)),
            entry(EntityType.DELIVERY_ITEM, new Binding(
                    PortPolicy.FULL_PORT,
                    """
                    SELECT di.DeliveryItemId, d.ClubId AS OperatingClubId,
                           di.DeliveryId, di.Position,
                           a.ArticleId AS ResolvedArticleId, di.ArticleNumber,
                           di.ItemText, di.AdditionalInformation, di.Quantity,
                           CAST(0 AS DECIMAL(12, 4)) AS ResolvedUnitPrice,
                           di.DiscountInPercent, di.UnitType,
                           di.CreatedOn, di.CreatedByUserId,
                           di.ModifiedOn, di.ModifiedByUserId,
                           di.DeletedOn, di.DeletedByUserId
                    FROM DeliveryItems di
                    JOIN Deliveries d ON d.DeliveryId = di.DeliveryId
                    LEFT JOIN Articles a
                        ON a.ClubId = d.ClubId
                       AND a.ArticleNumber = di.ArticleNumber
                       AND a.IsDeleted = 0
                    """,
                    "t_delivery_item",
                    """
                    INSERT INTO t_delivery_item (
                      id, operating_club_id, delivery_id, position,
                      article_id, article_number, item_text, additional_information,
                      quantity, unit_price, discount_in_percent, unit_type_code,
                      created_on, created_by_user_id, modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?)
                    """)),
            entry(EntityType.PERSON_FLIGHT_TIME_CREDIT, new Binding(
                    PortPolicy.FULL_PORT,
                    """
                    SELECT PersonFlightTimeCreditId, PersonId,
                           NoFlightTimeLimit, ValidUntil,
                           UseRuleForAllAircraftsExceptListed,
                           MatchedAircraftImmatriculations, DiscountInPercent,
                           CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                           DeletedOn, DeletedByUserId
                    FROM PersonFlightTimeCredits
                    """,
                    "t_person_flight_time_credit",
                    """
                    INSERT INTO t_person_flight_time_credit (
                      id, person_id,
                      no_flight_time_limit, valid_until,
                      use_rule_for_all_aircrafts_except_listed,
                      matched_aircraft_immatriculations, discount_in_percent,
                      created_on, created_by_user_id, modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?,
                            ?, ?,
                            ?,
                            ?, ?,
                            ?, ?, ?, ?,
                            ?, ?)
                    """)),
            entry(EntityType.PERSON_FLIGHT_TIME_CREDIT_TRANSACTION, new Binding(
                    PortPolicy.FULL_PORT,
                    """
                    SELECT tx.PersonFlightTimeCreditTransactionId,
                           tx.PersonFlightTimeCreditId,
                           d.DeliveryId AS ResolvedBalancedDeliveryId,
                           tx.BalanceDateTime, tx.NoFlightTimeLimit,
                           tx.CurrentFlightTimeBalanceInSeconds,
                           tx.FlightTimeBalanceInSeconds,
                           tx.OldFlightTimeBalanceInSeconds, tx.IsCurrent,
                           tx.CreatedOn, tx.CreatedByUserId,
                           tx.ModifiedOn, tx.ModifiedByUserId,
                           tx.DeletedOn, tx.DeletedByUserId
                    FROM (
                        SELECT PersonFlightTimeCreditTransactionId,
                               PersonFlightTimeCreditId, BalancedDeliveryId,
                               BalanceDateTime, NoFlightTimeLimit,
                               CurrentFlightTimeBalanceInSeconds,
                               FlightTimeBalanceInSeconds,
                               OldFlightTimeBalanceInSeconds, IsCurrent,
                               CreatedOn, CreatedByUserId,
                               ModifiedOn, ModifiedByUserId,
                               DeletedOn, DeletedByUserId,
                               ROW_NUMBER() OVER (
                                   PARTITION BY PersonFlightTimeCreditId
                                   ORDER BY BalanceDateTime DESC,
                                            PersonFlightTimeCreditTransactionId
                               ) AS current_rn
                        FROM PersonFlightTimeCreditTransactions
                        WHERE IsCurrent = 1
                    ) tx
                    LEFT JOIN Deliveries d ON d.DeliveryId = tx.BalancedDeliveryId
                    WHERE tx.current_rn = 1
                    """,
                    "t_person_flight_time_credit_transaction",
                    """
                    INSERT INTO t_person_flight_time_credit_transaction (
                      id, credit_id, balanced_delivery_id,
                      balance_date_time, no_flight_time_limit,
                      current_flight_time_balance_in_seconds,
                      flight_time_balance_in_seconds,
                      old_flight_time_balance_in_seconds, is_current,
                      created_on, created_by_user_id, modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (?, ?, ?,
                            ?, ?,
                            ?,
                            ?,
                            ?, ?,
                            ?, ?, ?, ?,
                            ?, ?)
                    """)),
            entry(EntityType.PERSON_CLUB, new Binding(
                    PortPolicy.FULL_PORT,
                    """
                    SELECT PersonId, ClubId, MemberNumber,
                           NULL AS MemberStateId,
                           IsMotorPilot, IsTowPilot, IsGliderInstructor, IsGliderPilot,
                           IsGliderTrainee, IsPassenger, IsWinchOperator, IsMotorInstructor,
                           ReceiveFlightReports, ReceiveAircraftReservationNotifications,
                           ReceivePlanningDayRoleReminder, IsActive,
                           CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                           DeletedOn, DeletedByUserId
                    FROM PersonClub
                    """,
                    "t_person_club",
                    """
                    INSERT INTO t_person_club (
                      id,
                      person_id, club_id, member_number, member_state_id,
                      is_motor_pilot, is_tow_pilot, is_glider_instructor, is_glider_pilot,
                      is_glider_trainee, is_passenger, is_winch_operator, is_motor_instructor,
                      receive_flight_reports, receive_aircraft_reservation_notifications,
                      receive_planning_day_role_reminder, is_active,
                      created_on, created_by_user_id, modified_on, modified_by_user_id,
                      deleted_on, deleted_by_user_id)
                    VALUES (gen_random_uuid(),
                            ?, ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?,
                            ?, ?,
                            ?, ?, ?, ?,
                            ?, ?)
                    """)),
            entry(EntityType.AUDIT_LOG, new Binding(
                    PortPolicy.FULL_PORT,
                    """
                    SELECT CONVERT(UNIQUEIDENTIFIER, CONVERT(BINARY(16), al.AuditLogId))
                               AS LegacyGuid,
                           al.AuditLogId,
                           al.EventDateUTC,
                           al.UserName,
                           actor.UserId AS ResolvedActorUserId,
                           CASE al.EventType
                                WHEN 0 THEN 'CREATE'
                                WHEN 1 THEN 'DELETE'
                                WHEN 2 THEN 'UPDATE'
                                WHEN 3 THEN 'UPDATE'
                                WHEN 4 THEN 'UPDATE'
                           END AS ResolvedAction,
                           REPLACE(al.TypeFullName, '%s', '')
                               AS ResolvedTargetEntityType,
                           TRY_CONVERT(UNIQUEIDENTIFIER, al.RecordId)
                               AS ResolvedTargetEntityId,
                           CASE WHEN TRY_CONVERT(UNIQUEIDENTIFIER, al.RecordId) IS NULL
                                THEN al.RecordId
                           END AS ResolvedLegacyTargetRecordId,
                           CASE WHEN actor.UserId IS NULL
                                 AND LTRIM(RTRIM(COALESCE(al.UserName, N''))) <> N''
                                THEN CONVERT(UNIQUEIDENTIFIER, SUBSTRING(HASHBYTES('SHA2_256',
                                         LOWER(LTRIM(RTRIM(
                                             CONVERT(NVARCHAR(4000), al.UserName))))), 1, 16))
                           END AS ResolvedLegacyOrphanActorId
                    FROM AuditLogs al
                    LEFT JOIN (
                        SELECT Username, UserId,
                               ROW_NUMBER() OVER (
                                   PARTITION BY Username
                                   ORDER BY CASE WHEN DeletedOn IS NULL THEN 0 ELSE 1 END,
                                            CreatedOn, UserId
                               ) AS actor_rn
                        FROM Users
                        WHERE UserId <> '%s'
                    ) actor ON actor.Username = al.UserName AND actor.actor_rn = 1
                    """.formatted(
                            LEGACY_AUDIT_ENTITY_NAMESPACE_STRIPPED_BY_THE_LEGACY_READ_PATH,
                            LEGACY_ASPNET_SYSTEM_USER_ID_NEVER_MIGRATED),
                    "t_mutation_audit_event",
                    """
                    INSERT INTO t_mutation_audit_event (
                      id, occurred_at,
                      actor_user_id, actor_keycloak_sub, tenant_club_id,
                      action, actor_kind,
                      target_entity_type, target_entity_id,
                      request_id, before_state, after_state,
                      failed, system_actor, http_status, failure_reason,
                      legacy_actor_user_id, legacy_int_id, legacy_target_record_id,
                      legacy_orphan_actor_id)
                    VALUES (?, ?,
                            ?, ?, ?,
                            ?, ?,
                            ?, ?,
                            ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?, ?,
                            ?)
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
