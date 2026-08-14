package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.migration.bundle.flight.StartTypeMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MapperLegacyBindingsTest {

    private static final List<String> LOCATION_LEGACY_COLUMNS = List.of(
            "LocationId", "ClubId", "LocationName", "LocationShortName",
            "CountryId", "LocationTypeId", "IcaoCode", "Latitude", "Longitude",
            "Elevation", "ElevationUnitType", "RunwayDirection", "RunwayLength",
            "RunwayLengthUnitType", "AirportFrequency", "Description",
            "SortIndicator", "IsInboundRouteRequired", "IsOutboundRouteRequired",
            "IsFastEntryRecord", "CreatedOn", "CreatedByUserId", "ModifiedOn",
            "ModifiedByUserId", "DeletedOn", "DeletedByUserId");

    private static final List<String> INOUTBOUND_POINT_LEGACY_COLUMNS = List.of(
            "InOutboundPointId", "LocationId", "ClubId", "InOutboundPointName",
            "IsInboundPoint", "IsOutboundPoint", "CreatedOn", "CreatedByUserId",
            "ModifiedOn", "ModifiedByUserId", "DeletedOn", "DeletedByUserId");

    private static final List<String> PERSON_LEGACY_COLUMNS = List.of(
            "PersonId", "Lastname", "Firstname", "Midname", "CompanyName",
            "AddressLine1", "AddressLine2", "Zip", "City", "Region", "CountryId",
            "PrivatePhone", "MobilePhone", "BusinessPhone", "FaxNumber",
            "EmailPrivate", "EmailBusiness", "PreferMailToBusinessMail", "Birthday",
            "HasMotorPilotLicence", "HasTowPilotLicence", "HasGliderInstructorLicence",
            "HasGliderPilotLicence", "HasGliderTraineeLicence", "HasGliderPAXLicence",
            "HasTMGLicence", "HasWinchOperatorLicence", "HasMotorInstructorLicence",
            "HasPartMLicence", "LicenceNumber", "MedicalClass1ExpireDate",
            "MedicalClass2ExpireDate", "MedicalLaplExpireDate",
            "GliderInstructorLicenceExpireDate", "MotorInstructorLicenceExpireDate",
            "PartMLicenceExpireDate", "HasGliderTowingStartPermission",
            "HasGliderSelfStartPermission", "HasGliderWinchStartPermission",
            "SpotLink", "ReceiveOwnedAircraftStatisticReports", "EnableAddress",
            "IsFastEntryRecord", "CreatedOn", "CreatedByUserId", "ModifiedOn",
            "ModifiedByUserId", "DeletedOn", "DeletedByUserId");

    @Test
    void locationIsRegistered() {
        assertThat(MapperLegacyBindings.isRegistered(EntityType.LOCATION))
                .as("LOCATION must be bound so the export jar / ProducerHarness "
                        + "stop throwing \"No legacy binding registered\"")
                .isTrue();
    }

    @Test
    void inOutboundPointIsRegistered() {
        assertThat(MapperLegacyBindings.isRegistered(EntityType.INOUTBOUND_POINT))
                .as("INOUTBOUND_POINT must be bound — it is the Location aggregate child")
                .isTrue();
    }

    @Test
    void locationIsTenantScopedFullPort() {
        assertThat(MapperLegacyBindings.portPolicy(EntityType.LOCATION))
                .as("Location is FULL_PORT per V7 (club_id IS the @TenantId)")
                .isEqualTo(MapperLegacyBindings.PortPolicy.FULL_PORT);
    }

    @Test
    void inOutboundPointIsFullPort() {
        assertThat(MapperLegacyBindings.portPolicy(EntityType.INOUTBOUND_POINT))
                .isEqualTo(MapperLegacyBindings.PortPolicy.FULL_PORT);
    }

    @Test
    void locationSelectProjectsEveryColumnTheMapperReads() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.LOCATION);
        for (String legacyColumn : LOCATION_LEGACY_COLUMNS) {
            assertThat(select)
                    .as("LocationMapper.writeNdjson reads %s from the ResultSet — the "
                            + "bound SELECT must project it (else: silent NULL)", legacyColumn)
                    .contains(legacyColumn);
        }
    }

    @Test
    void locationSelectAliasesTheFanOutPartnerClubId() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.LOCATION).toUpperCase();
        assertThat(select)
                .as("the fan-out partner Club must be aliased AS ClubId on the cursor")
                .contains("AS CLUBID");
    }

    @Test
    void locationSelectProjectsTheLocationTypeIntCupIdNotTheGuidFk() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.LOCATION).toUpperCase();
        assertThat(select)
                .as("SELECT must JOIN LocationTypes to source the int CupId")
                .contains("LOCATIONTYPES");
        assertThat(select)
                .as("SELECT must project LocationTypeCupId aliased AS LocationTypeId "
                        + "so writeNdjson's getInt reads the int code, not the GUID")
                .contains("LOCATIONTYPECUPID AS LOCATIONTYPEID");
    }

    @Test
    void inOutboundPointSelectProjectsEveryColumnTheMapperReads() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.INOUTBOUND_POINT);
        for (String legacyColumn : INOUTBOUND_POINT_LEGACY_COLUMNS) {
            assertThat(select)
                    .as("InOutboundPointMapper.writeNdjson reads %s — the bound SELECT "
                            + "must project it", legacyColumn)
                    .contains(legacyColumn);
        }
    }

    @Test
    void inOutboundPointSelectFansOutOverTheSameParentPartnerSetAsLocation() {
        String iop = MapperLegacyBindings.selectForProducer(EntityType.INOUTBOUND_POINT)
                .toUpperCase();
        assertThat(iop)
                .as("child must alias its own fan-out partner Club AS ClubId")
                .contains("AS CLUBID");
        assertThat(iop)
                .as("child fan-out joins the parent Location partner set "
                        + "(Clubs.HomebaseId + Flights start/landing via OwnerId)")
                .contains("HOMEBASEID")
                .contains("STARTLOCATIONID")
                .contains("LDGLOCATIONID");
    }

    @Test
    void selectTargetsTheLegacyTablesByName() {
        assertThat(MapperLegacyBindings.selectForProducer(EntityType.LOCATION))
                .as("base table is the legacy Locations table")
                .contains("Locations");
        assertThat(MapperLegacyBindings.selectForProducer(EntityType.INOUTBOUND_POINT))
                .contains("InOutboundPoints");
    }

    @Test
    void personIsRegistered() {
        assertThat(MapperLegacyBindings.isRegistered(EntityType.PERSON))
                .as("PERSON must be bound so the producer exports t_person and "
                        + "USER.person_id (a passed-through legacy GUID) resolves "
                        + "against fk_user_person_id at ingest (J-0c T-21)")
                .isTrue();
    }

    @Test
    void personIsFullPort() {
        assertThat(MapperLegacyBindings.portPolicy(EntityType.PERSON))
                .as("Person is the cross-tenant FULL_PORT aggregate root (ADR 0008)")
                .isEqualTo(MapperLegacyBindings.PortPolicy.FULL_PORT);
    }

    @Test
    void personSelectProjectsEveryColumnTheMapperReads() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.PERSON);
        for (String legacyColumn : PERSON_LEGACY_COLUMNS) {
            assertThat(select)
                    .as("PersonMapper.writeNdjson reads %s from the ResultSet — the "
                            + "bound SELECT must project it (else: silent NULL)", legacyColumn)
                    .contains(legacyColumn);
        }
    }

    @Test
    void personSelectTargetsTheLegacyPersonsTable() {
        assertThat(MapperLegacyBindings.selectForProducer(EntityType.PERSON))
                .as("base table is the legacy Persons table")
                .contains("Persons");
    }

    @Test
    void personHasNoConsumerInsertOnlyWriteAccessIsValidated() {
        String insert = MapperLegacyBindings.insertForConsumer(EntityType.PERSON);
        assertThat(insert)
                .as("PERSON FULL_PORT consumer INSERT targets t_person")
                .contains("INSERT INTO t_person");
        assertThat(insert.toLowerCase(java.util.Locale.ROOT))
                .as("t_person is cross-tenant — the INSERT must not bind a club_id")
                .doesNotContain("club_id");
    }

    @Test
    void unregisteredEntityStillFailsLoudly() {
        assertThatThrownBy(() -> MapperLegacyBindings.require(EntityType.MEMBER_STATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No legacy binding registered");
    }


    private static final List<String> AIRCRAFT_LEGACY_COLUMNS = List.of(
            "AircraftId", "ManagingClubId", "AircraftOwnerClubId", "AircraftType",
            "ManufacturerName", "AircraftModel", "Immatriculation", "CompetitionSign",
            "FLARMId", "AircraftSerialNumber", "YearOfManufacture", "NoiseClass",
            "NoiseLevel", "MTOM", "NrOfSeats", "AircraftOwnerPersonId",
            "FlightOperatingCounterUnitTypeId", "EngineOperatingCounterUnitTypeId",
            "HomebaseId", "SpotLink", "IsTowingOrWinchRequired", "IsTowingstartAllowed",
            "IsWinchstartAllowed", "IsTowingAircraft", "IsFastEntryRecord", "Comment",
            "DaecIndex", "CreatedOn", "CreatedByUserId", "ModifiedOn", "ModifiedByUserId",
            "DeletedOn", "DeletedByUserId");

    private static final List<String> AIRCRAFT_AIRCRAFT_STATE_LEGACY_COLUMNS = List.of(
            "AircraftId", "AircraftState", "ValidFrom", "ValidTo", "NoticedByPersonId",
            "Remarks", "CreatedOn", "CreatedByUserId", "ModifiedOn", "ModifiedByUserId",
            "DeletedOn", "DeletedByUserId");

    private static final List<String> AIRCRAFT_OPERATING_COUNTER_LEGACY_COLUMNS = List.of(
            "AircraftOperatingCounterId", "AircraftId", "AtDateTime",
            "TotalTowedGliderStarts", "TotalWinchLaunchStarts", "TotalSelfStarts",
            "FlightOperatingCounterInSeconds", "EngineOperatingCounterInSeconds",
            "NextMaintenanceAtFlightOperatingCounterInSeconds",
            "NextMaintenanceAtEngineOperatingCounterInSeconds",
            "CreatedOn", "CreatedByUserId", "ModifiedOn", "ModifiedByUserId",
            "DeletedOn", "DeletedByUserId");

    @Test
    void aircraftAndChildrenAreRegistered() {
        assertThat(MapperLegacyBindings.isRegistered(EntityType.AIRCRAFT))
                .as("AIRCRAFT must be bound (J-1 T-04) so the Aircraft register migrates")
                .isTrue();
        assertThat(MapperLegacyBindings.isRegistered(EntityType.AIRCRAFT_AIRCRAFT_STATE))
                .as("AIRCRAFT_AIRCRAFT_STATE (aggregate-internal) must be bound")
                .isTrue();
        assertThat(MapperLegacyBindings.isRegistered(EntityType.AIRCRAFT_OPERATING_COUNTER))
                .as("AIRCRAFT_OPERATING_COUNTER (aggregate-internal) must be bound")
                .isTrue();
    }

    @Test
    void aircraftAndChildrenAreFullPort() {
        assertThat(MapperLegacyBindings.portPolicy(EntityType.AIRCRAFT))
                .as("Aircraft is the cross-tenant FULL_PORT aggregate root (ADR 0008)")
                .isEqualTo(MapperLegacyBindings.PortPolicy.FULL_PORT);
        assertThat(MapperLegacyBindings.portPolicy(EntityType.AIRCRAFT_AIRCRAFT_STATE))
                .isEqualTo(MapperLegacyBindings.PortPolicy.FULL_PORT);
        assertThat(MapperLegacyBindings.portPolicy(EntityType.AIRCRAFT_OPERATING_COUNTER))
                .isEqualTo(MapperLegacyBindings.PortPolicy.FULL_PORT);
    }

    @Test
    void aircraftSelectProjectsEveryColumnTheMapperReads() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.AIRCRAFT);
        for (String legacyColumn : AIRCRAFT_LEGACY_COLUMNS) {
            assertThat(select)
                    .as("AircraftMapper.writeNdjson reads %s — the bound SELECT must "
                            + "project it (else: silent NULL)", legacyColumn)
                    .contains(legacyColumn);
        }
    }

    @Test
    void aircraftSelectDerivesManagingClubIdFromOwnerClubId() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.AIRCRAFT).toUpperCase();
        assertThat(select)
                .as("managing_club_id source must be AircraftOwnerClubId, aliased AS ManagingClubId")
                .contains("AIRCRAFTOWNERCLUBID AS MANAGINGCLUBID");
    }

    @Test
    void aircraftSelectProjectsTheRealAircraftTypeColumn() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.AIRCRAFT).toUpperCase();
        assertThat(select)
                .as("SELECT projects the real legacy AircraftType column")
                .contains("AIRCRAFTTYPE");
        assertThat(select)
                .as("SELECT must NOT reference the non-existent AircraftTypeId column")
                .doesNotContain("AIRCRAFTTYPEID");
    }

    @Test
    void aircraftSelectTargetsTheLegacyAircraftsTable() {
        assertThat(MapperLegacyBindings.selectForProducer(EntityType.AIRCRAFT))
                .as("base table is the legacy Aircrafts table")
                .contains("Aircrafts");
    }

    @Test
    void aircraftConsumerInsertTargetsTAircraftWithManagingClubId() {
        String insert = MapperLegacyBindings.insertForConsumer(EntityType.AIRCRAFT);
        assertThat(insert)
                .as("AIRCRAFT FULL_PORT consumer INSERT targets t_aircraft")
                .contains("INSERT INTO t_aircraft");
        assertThat(insert.toLowerCase(java.util.Locale.ROOT))
                .as("t_aircraft is tenant-scoped via managing_club_id (V10) — the INSERT binds it")
                .contains("managing_club_id");
    }

    @Test
    void aircraftAircraftStateSelectProjectsEveryColumnTheMapperReads() {
        String select =
                MapperLegacyBindings.selectForProducer(EntityType.AIRCRAFT_AIRCRAFT_STATE);
        for (String legacyColumn : AIRCRAFT_AIRCRAFT_STATE_LEGACY_COLUMNS) {
            assertThat(select)
                    .as("AircraftAircraftStateMapper.writeNdjson reads %s — the bound "
                            + "SELECT must project it", legacyColumn)
                    .contains(legacyColumn);
        }
        assertThat(select)
                .as("base table is the legacy AircraftAircraftStates table")
                .contains("AircraftAircraftStates");
        assertThat(select.toUpperCase())
                .as("SELECT projects the real legacy AircraftState column")
                .contains("AIRCRAFTSTATE");
        assertThat(select.toUpperCase())
                .as("SELECT must NOT reference the non-existent AircraftStateId column")
                .doesNotContain("AIRCRAFTSTATEID");
    }

    @Test
    void aircraftOperatingCounterSelectProjectsEveryColumnTheMapperReads() {
        String select =
                MapperLegacyBindings.selectForProducer(EntityType.AIRCRAFT_OPERATING_COUNTER);
        for (String legacyColumn : AIRCRAFT_OPERATING_COUNTER_LEGACY_COLUMNS) {
            assertThat(select)
                    .as("AircraftOperatingCounterMapper.writeNdjson reads %s — the bound "
                            + "SELECT must project it", legacyColumn)
                    .contains(legacyColumn);
        }
        assertThat(select)
                .as("base table is the legacy AircraftOperatingCounters table")
                .contains("AircraftOperatingCounters");
    }


    private static final List<String> FLIGHT_LEGACY_COLUMNS = List.of(
            "FlightId", "OwnerId", "AircraftId", "FlightDate", "StartDateTime",
            "LdgDateTime", "BlockStartDateTime", "BlockEndDateTime",
            "StartLocationId", "LdgLocationId", "StartRunway", "LdgRunway",
            "OutboundRoute", "InboundRoute", "FlightTypeId", "IsSoloFlight",
            "StartType", "TowFlightId", "NrOfLdgs", "NrOfLdgsOnStartLocation",
            "NoStartTimeInformation", "NoLdgTimeInformation", "AirStateId",
            "ProcessStateId", "FlightAircraftType",
            "EngineStartOperatingCounterInSeconds",
            "EngineEndOperatingCounterInSeconds", "Comment", "IncidentComment",
            "ValidationErrors", "CouponNumber", "FlightCostBalanceType",
            "DeliveryCreatedOn", "ValidatedOn", "NrOfPassengers", "StartPosition",
            "FlightReportSentOn", "CreatedOn", "CreatedByUserId", "ModifiedOn",
            "ModifiedByUserId", "DeletedOn", "DeletedByUserId");

    private static final List<String> FLIGHT_CREW_LEGACY_COLUMNS = List.of(
            "FlightCrewId", "FlightId", "PersonId", "FlightCrewType",
            "BeginFlightDateTime", "EndFlightDateTime", "BeginInstructionDateTime",
            "EndInstructionDateTime", "NrOfLdgs", "NrOfStarts",
            "DeletedOn", "DeletedByUserId");

    @Test
    void flightAndClosureAreRegistered() {
        assertThat(MapperLegacyBindings.isRegistered(EntityType.FLIGHT))
                .as("FLIGHT must be bound (J-2 T-07) so the Flight register migrates")
                .isTrue();
        assertThat(MapperLegacyBindings.isRegistered(EntityType.FLIGHT_CREW))
                .as("FLIGHT_CREW (aggregate-internal) must be bound")
                .isTrue();
        assertThat(MapperLegacyBindings.isRegistered(EntityType.START_TYPE))
                .as("START_TYPE must be bound — it is a FlightMapper.foreignKeys() target")
                .isTrue();
        assertThat(MapperLegacyBindings.isRegistered(EntityType.FLIGHT_TYPE))
                .as("FLIGHT_TYPE must be bound — it is a FlightMapper.foreignKeys() target")
                .isTrue();
    }

    @Test
    void flightIsTenantScopedFullPort() {
        assertThat(MapperLegacyBindings.portPolicy(EntityType.FLIGHT))
                .as("Flight is FULL_PORT, tenant-scoped via operating_club_id (V3)")
                .isEqualTo(MapperLegacyBindings.PortPolicy.FULL_PORT);
        assertThat(MapperLegacyBindings.portPolicy(EntityType.FLIGHT_CREW))
                .isEqualTo(MapperLegacyBindings.PortPolicy.FULL_PORT);
        assertThat(MapperLegacyBindings.portPolicy(EntityType.FLIGHT_TYPE))
                .isEqualTo(MapperLegacyBindings.PortPolicy.FULL_PORT);
    }

    @Test
    void startTypeIsSystemGlobal() {
        assertThat(MapperLegacyBindings.portPolicy(EntityType.START_TYPE))
                .as("StartType is a SYSTEM_GLOBAL reference (V2 seed by code, no "
                        + "legacy_int_id), like Language / ClubState")
                .isEqualTo(MapperLegacyBindings.PortPolicy.SYSTEM_GLOBAL);
    }

    @Test
    void flightSelectProjectsEveryColumnTheMapperReads() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.FLIGHT);
        for (String legacyColumn : FLIGHT_LEGACY_COLUMNS) {
            assertThat(select)
                    .as("FlightMapper.writeNdjson reads %s — the bound SELECT must "
                            + "project it (else: silent NULL / export abort)", legacyColumn)
                    .contains(legacyColumn);
        }
    }

    @Test
    void flightSelectSourcesOperatingClubFromOwnerIdNotOwnerClubId() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.FLIGHT).toUpperCase();
        assertThat(select)
                .as("SELECT projects the real legacy OwnerId column")
                .contains("OWNERID");
        assertThat(select)
                .as("SELECT must NOT reference the non-existent OwnerClubId column")
                .doesNotContain("OWNERCLUBID");
    }

    @Test
    void flightSelectTargetsTheLegacyFlightsTable() {
        assertThat(MapperLegacyBindings.selectForProducer(EntityType.FLIGHT))
                .as("base table is the legacy Flights table")
                .contains("Flights");
    }

    @Test
    void flightConsumerInsertTargetsTFlightWithOperatingClubId() {
        String insert = MapperLegacyBindings.insertForConsumer(EntityType.FLIGHT);
        assertThat(insert)
                .as("FLIGHT FULL_PORT consumer INSERT targets t_flight")
                .contains("INSERT INTO t_flight");
        assertThat(insert.toLowerCase(java.util.Locale.ROOT))
                .as("t_flight is tenant-scoped via operating_club_id (V3) — the INSERT binds it")
                .contains("operating_club_id");
        assertThat(insert.toLowerCase(java.util.Locale.ROOT))
                .as("V13 dropped air_state_id — the INSERT must not bind it")
                .doesNotContain("air_state_id");
        assertThat(insert.toLowerCase(java.util.Locale.ROOT))
                .as("V13 added flight_plan_opened_on (the only surviving air-state info)")
                .contains("flight_plan_opened_on");
    }

    @Test
    void flightCrewSelectProjectsEveryColumnTheMapperReads() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.FLIGHT_CREW);
        for (String legacyColumn : FLIGHT_CREW_LEGACY_COLUMNS) {
            assertThat(select)
                    .as("FlightCrewMapper.writeNdjson reads %s — the bound SELECT "
                            + "must project it", legacyColumn)
                    .contains(legacyColumn);
        }
        assertThat(select)
                .as("base table is the legacy FlightCrew table")
                .contains("FlightCrew");
    }

    @Test
    void flightTypeSelectProjectsEveryColumnTheMapperReads() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.FLIGHT_TYPE);
        for (String legacyColumn : List.of(
                "FlightTypeId", "ClubId", "FlightTypeName", "FlightCode",
                "InstructorRequired", "ObserverPilotOrInstructorRequired",
                "IsCheckFlight", "IsPassengerFlight", "IsSoloFlight",
                "IsForGliderFlights", "IsForTowFlights", "IsForMotorFlights",
                "IsFlightCostBalanceSelectable", "IsCouponNumberRequired",
                "IsForAircraftReservationType", "MinNrOfAircraftSeatsRequired",
                "CreatedOn", "CreatedByUserId", "ModifiedOn", "ModifiedByUserId",
                "DeletedOn", "DeletedByUserId")) {
            assertThat(select)
                    .as("FlightTypeMapper.writeNdjson reads %s", legacyColumn)
                    .contains(legacyColumn);
        }
        assertThat(select).contains("FlightTypes");
    }

    @Test
    void startTypeSelectProjectsTheStartTypeId() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.START_TYPE);
        assertThat(select)
                .as("StartTypeMapper.writeNdjson reads StartTypeId")
                .contains("StartTypeId");
        assertThat(select).contains("StartTypes");
    }

    @Test
    void startTypeClosureEnumeratesTheFullLegacyAircraftStartTypeEnum() {
        Map<UUID, UUID> closure = StartTypeMapper.legacyEnumIdToSeedPk();

        assertThat(StartTypeMapper.LEGACY_ENUM_IDS)
                .as("the START_TYPE closure must cover the full legacy "
                        + "AircraftStartType enum (1..5) — add a missing value here, "
                        + "do NOT weaken the ingest FK-leak guard")
                .containsExactly(1, 2, 3, 4, 5);

        for (int legacyId : List.of(1, 2, 3, 4, 5)) {
            UUID synthetic = new UUID(0L, legacyId);
            assertThat(closure)
                    .as("legacy AircraftStartType %d (UUID(0,%d)) must resolve to a "
                            + "t_start_type seed PK in the bundle closure — the real "
                            + "FLSTest SelfStart(3) flight 400'd the ingest because it "
                            + "did not (J-2 T-39)", legacyId, legacyId)
                    .containsKey(synthetic);
        }

        assertThat(closure)
                .as("SelfStart(3) must map to the V2 SELF_START seed PK")
                .containsEntry(new UUID(0L, 3L), SeedReferenceUuids.startTypeByCode("SELF_START"));

        assertThat(closure.values().stream().distinct().count())
                .as("the 5 legacy start types map to 5 distinct t_start_type seed PKs")
                .isEqualTo(5L);
    }


    private static final List<String> AIRCRAFT_RESERVATION_LEGACY_COLUMNS = List.of(
            "AircraftReservationId", "ClubId", "AircraftId", "Start", "End",
            "IsAllDayReservation", "PilotPersonId", "SecondCrewPersonId", "LocationId",
            "AircraftReservationTypeId", "FlightTypeId", "Remarks",
            "CreatedOn", "CreatedByUserId", "ModifiedOn", "ModifiedByUserId",
            "DeletedOn", "DeletedByUserId");

    private static final List<String> AIRCRAFT_RESERVATION_TYPE_LEGACY_COLUMNS = List.of(
            "AircraftReservationTypeId", "ClubId", "AircraftReservationTypeName",
            "IsInstructorRequired", "IsMaintenance", "IsActive", "Remarks",
            "CreatedOn", "CreatedByUserId", "ModifiedOn", "ModifiedByUserId",
            "DeletedOn", "DeletedByUserId");

    @Test
    void aircraftReservationAndTypeAreRegistered() {
        assertThat(MapperLegacyBindings.isRegistered(EntityType.AIRCRAFT_RESERVATION))
                .as("AIRCRAFT_RESERVATION must be bound (J-5 T-07) so the reservation register migrates")
                .isTrue();
        assertThat(MapperLegacyBindings.isRegistered(EntityType.AIRCRAFT_RESERVATION_TYPE))
                .as("AIRCRAFT_RESERVATION_TYPE must be bound — it is an "
                        + "AircraftReservationMapper.foreignKeys() target")
                .isTrue();
    }

    @Test
    void aircraftReservationAndTypeAreTenantScopedFullPort() {
        assertThat(MapperLegacyBindings.portPolicy(EntityType.AIRCRAFT_RESERVATION))
                .as("AircraftReservation is FULL_PORT, tenant-scoped via operating_club_id (V4)")
                .isEqualTo(MapperLegacyBindings.PortPolicy.FULL_PORT);
        assertThat(MapperLegacyBindings.portPolicy(EntityType.AIRCRAFT_RESERVATION_TYPE))
                .isEqualTo(MapperLegacyBindings.PortPolicy.FULL_PORT);
    }

    @Test
    void aircraftReservationSelectProjectsEveryColumnTheMapperReads() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.AIRCRAFT_RESERVATION);
        for (String legacyColumn : AIRCRAFT_RESERVATION_LEGACY_COLUMNS) {
            assertThat(select)
                    .as("AircraftReservationMapper.writeNdjson reads %s — the bound SELECT "
                            + "must project it (else: silent NULL / export abort)", legacyColumn)
                    .contains(legacyColumn);
        }
        assertThat(select)
                .as("base table is the legacy AircraftReservations table")
                .contains("AircraftReservations");
    }

    @Test
    void aircraftReservationSelectUsesPostV1923ColumnNamesNotTheDroppedV10Ones() {
        String select =
                MapperLegacyBindings.selectForProducer(EntityType.AIRCRAFT_RESERVATION).toUpperCase();
        assertThat(select)
                .as("SELECT projects the post-v1.9.23 AircraftReservationTypeId column")
                .contains("AIRCRAFTRESERVATIONTYPEID");
        assertThat(select)
                .as("SELECT must NOT reference the v1.9.23-dropped InstructorPersonId column")
                .doesNotContain("INSTRUCTORPERSONID");
    }

    @Test
    void aircraftReservationConsumerInsertTargetsTAircraftReservationWithOperatingClubId() {
        String insert = MapperLegacyBindings.insertForConsumer(EntityType.AIRCRAFT_RESERVATION);
        assertThat(insert)
                .as("AIRCRAFT_RESERVATION FULL_PORT consumer INSERT targets t_aircraft_reservation")
                .contains("INSERT INTO t_aircraft_reservation");
        assertThat(insert.toLowerCase(java.util.Locale.ROOT))
                .as("t_aircraft_reservation is tenant-scoped via operating_club_id (V4) — "
                        + "the INSERT binds it")
                .contains("operating_club_id");
        assertThat(insert.toLowerCase(java.util.Locale.ROOT))
                .as("reservation_range is GENERATED ALWAYS in V4 — the INSERT must NOT bind it")
                .doesNotContain("reservation_range");
    }

    @Test
    void aircraftReservationTypeSelectProjectsEveryColumnTheMapperReads() {
        String select =
                MapperLegacyBindings.selectForProducer(EntityType.AIRCRAFT_RESERVATION_TYPE);
        for (String legacyColumn : AIRCRAFT_RESERVATION_TYPE_LEGACY_COLUMNS) {
            assertThat(select)
                    .as("AircraftReservationTypeMapper.writeNdjson reads %s — the bound SELECT "
                            + "must project it", legacyColumn)
                    .contains(legacyColumn);
        }
        assertThat(select)
                .as("base table is the legacy AircraftReservationTypes table")
                .contains("AircraftReservationTypes");
    }

    @Test
    void aircraftReservationTypeConsumerInsertTargetsTAircraftReservationType() {
        String insert =
                MapperLegacyBindings.insertForConsumer(EntityType.AIRCRAFT_RESERVATION_TYPE);
        assertThat(insert)
                .as("AIRCRAFT_RESERVATION_TYPE FULL_PORT consumer INSERT targets "
                        + "t_aircraft_reservation_type")
                .contains("INSERT INTO t_aircraft_reservation_type");
        assertThat(insert.toLowerCase(java.util.Locale.ROOT))
                .as("t_aircraft_reservation_type is tenant-scoped via operating_club_id (V4)")
                .contains("operating_club_id");
    }


    private static final List<String> PLANNING_DAY_LEGACY_COLUMNS = List.of(
            "PlanningDayId", "ClubId", "Day", "LocationId", "Remarks",
            "CreatedOn", "CreatedByUserId", "ModifiedOn", "ModifiedByUserId",
            "DeletedOn", "DeletedByUserId");

    private static final List<String> PLANNING_DAY_ASSIGNMENT_LEGACY_COLUMNS = List.of(
            "PlanningDayAssignmentId", "OperatingClubId", "AssignedPlanningDayId",
            "AssignedPersonId", "AssignmentTypeId", "Remarks",
            "CreatedOn", "CreatedByUserId", "ModifiedOn", "ModifiedByUserId",
            "DeletedOn", "DeletedByUserId");

    private static final List<String> PLANNING_DAY_ASSIGNMENT_TYPE_LEGACY_COLUMNS = List.of(
            "PlanningDayAssignmentTypeId", "ClubId", "AssignmentTypeName",
            "RequiredNrOfAssignments",
            "CreatedOn", "CreatedByUserId", "ModifiedOn", "ModifiedByUserId",
            "DeletedOn", "DeletedByUserId");

    @Test
    void planningDayTrioIsRegistered() {
        assertThat(MapperLegacyBindings.isRegistered(EntityType.PLANNING_DAY))
                .as("PLANNING_DAY must be bound (J-6 T-11) so the planning register migrates")
                .isTrue();
        assertThat(MapperLegacyBindings.isRegistered(EntityType.PLANNING_DAY_ASSIGNMENT))
                .as("PLANNING_DAY_ASSIGNMENT must be bound — it is a "
                        + "PlanningDayMapper aggregate child")
                .isTrue();
        assertThat(MapperLegacyBindings.isRegistered(EntityType.PLANNING_DAY_ASSIGNMENT_TYPE))
                .as("PLANNING_DAY_ASSIGNMENT_TYPE must be bound — it is a "
                        + "PlanningDayAssignmentMapper.foreignKeys() target")
                .isTrue();
    }

    @Test
    void planningDayTrioIsTenantScopedFullPort() {
        assertThat(MapperLegacyBindings.portPolicy(EntityType.PLANNING_DAY))
                .as("PlanningDay is FULL_PORT, tenant-scoped via operating_club_id (V4)")
                .isEqualTo(MapperLegacyBindings.PortPolicy.FULL_PORT);
        assertThat(MapperLegacyBindings.portPolicy(EntityType.PLANNING_DAY_ASSIGNMENT))
                .isEqualTo(MapperLegacyBindings.PortPolicy.FULL_PORT);
        assertThat(MapperLegacyBindings.portPolicy(EntityType.PLANNING_DAY_ASSIGNMENT_TYPE))
                .isEqualTo(MapperLegacyBindings.PortPolicy.FULL_PORT);
    }

    @Test
    void planningDaySelectProjectsEveryColumnTheMapperReads() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.PLANNING_DAY);
        for (String legacyColumn : PLANNING_DAY_LEGACY_COLUMNS) {
            assertThat(select)
                    .as("PlanningDayMapper.writeNdjson reads %s — the bound SELECT must "
                            + "project it (else: silent NULL / export abort)", legacyColumn)
                    .contains(legacyColumn);
        }
        assertThat(select)
                .as("base table is the legacy PlanningDays table")
                .contains("PlanningDays");
    }

    @Test
    void planningDaySelectDedupesKeepFirstOnTheV4UniquePartialKey() {
        String normalised = MapperLegacyBindings.selectForProducer(EntityType.PLANNING_DAY)
                .toUpperCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", " ");
        assertThat(normalised)
                .as("PLANNING_DAY SELECT must keep-first per (ClubId, Day, LocationId) "
                        + "via ROW_NUMBER() OVER — else duplicate legacy rows 23505 on "
                        + "ux_pln_club_date_loc at ingest")
                .contains("ROW_NUMBER() OVER")
                .contains("PARTITION BY CLUBID, DAY, LOCATIONID")
                .contains("ORDER BY CREATEDON, PLANNINGDAYID")
                .contains("WHERE RN = 1");
    }

    @Test
    void planningDayConsumerInsertTargetsTPlanningDayWithOperatingClubId() {
        String insert = MapperLegacyBindings.insertForConsumer(EntityType.PLANNING_DAY);
        assertThat(insert)
                .as("PLANNING_DAY FULL_PORT consumer INSERT targets t_planning_day")
                .contains("INSERT INTO t_planning_day");
        assertThat(insert.toLowerCase(java.util.Locale.ROOT))
                .as("t_planning_day is tenant-scoped via operating_club_id (V4)")
                .contains("operating_club_id");
    }

    @Test
    void planningDayAssignmentSelectDenormalisesOperatingClubIdByJoiningPlanningDays() {
        String select =
                MapperLegacyBindings.selectForProducer(EntityType.PLANNING_DAY_ASSIGNMENT);
        for (String legacyColumn : PLANNING_DAY_ASSIGNMENT_LEGACY_COLUMNS) {
            assertThat(select)
                    .as("PlanningDayAssignmentMapper.writeNdjson reads %s — the bound "
                            + "SELECT must project it", legacyColumn)
                    .contains(legacyColumn);
        }
        String upper = select.toUpperCase(java.util.Locale.ROOT);
        assertThat(upper)
                .as("operating_club_id is sourced by JOINing the parent PlanningDays")
                .contains("JOIN PLANNINGDAYS");
        assertThat(upper)
                .as("the parent's ClubId is aliased AS OperatingClubId on the cursor")
                .contains("AS OPERATINGCLUBID");
        assertThat(upper.replaceAll("\\s+", " "))
                .as("the assignment SELECT remaps planning_day_id onto the kept-first "
                        + "survivor per (ClubId, Day, LocationId) — else a dropped-day "
                        + "assignment FK-violates (23503) at ingest")
                .contains("FIRST_VALUE(PLANNINGDAYID) OVER")
                .contains("PARTITION BY CLUBID, DAY, LOCATIONID")
                .contains("ORDER BY CREATEDON, PLANNINGDAYID")
                .contains("AS ASSIGNEDPLANNINGDAYID");
    }

    @Test
    void planningDayAssignmentTypeSelectAliasesTheRealRequiredNrColumn() {
        String select =
                MapperLegacyBindings.selectForProducer(EntityType.PLANNING_DAY_ASSIGNMENT_TYPE);
        for (String legacyColumn : PLANNING_DAY_ASSIGNMENT_TYPE_LEGACY_COLUMNS) {
            assertThat(select)
                    .as("PlanningDayAssignmentTypeMapper.writeNdjson reads %s — the bound "
                            + "SELECT must project it", legacyColumn)
                    .contains(legacyColumn);
        }
        assertThat(select)
                .as("base table is the legacy PlanningDayAssignmentTypes table")
                .contains("PlanningDayAssignmentTypes");
        assertThat(select)
                .as("SELECT must project the real RequiredNrOfPlanningDayAssignments column")
                .contains("RequiredNrOfPlanningDayAssignments AS RequiredNrOfAssignments");
    }

    private static final List<String> ARTICLE_LEGACY_COLUMNS = List.of(
            "ArticleId", "ClubId", "ArticleNumber", "ArticleName",
            "ArticleInfo", "Description", "IsActive",
            "CreatedOn", "CreatedByUserId", "ModifiedOn", "ModifiedByUserId",
            "DeletedOn", "DeletedByUserId");

    @Test
    void articleIsRegisteredTenantScopedFullPort() {
        assertThat(MapperLegacyBindings.isRegistered(EntityType.ARTICLE))
                .as("ARTICLE must be bound so the article register exports — "
                        + "the DeliveryItem.article_id RESTRICT FK target")
                .isTrue();
        assertThat(MapperLegacyBindings.portPolicy(EntityType.ARTICLE))
                .as("Article is FULL_PORT, tenant-scoped via operating_club_id (V3)")
                .isEqualTo(MapperLegacyBindings.PortPolicy.FULL_PORT);
    }

    @Test
    void articleSelectProjectsEveryColumnTheMapperReads() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.ARTICLE);
        for (String legacyColumn : ARTICLE_LEGACY_COLUMNS) {
            assertThat(select)
                    .as("ArticleMapper.writeNdjson reads %s — the bound SELECT must "
                            + "project it (else: silent NULL / export abort)", legacyColumn)
                    .contains(legacyColumn);
        }
        assertThat(select)
                .as("base table is the legacy Articles table")
                .contains("Articles");
    }

    @Test
    void articleResolvesItsClubFkThroughOperatingClubIdNotTheConvention() {
        Mapper article = KnownMappers.all().stream()
                .filter(m -> m.entityType() == EntityType.ARTICLE)
                .findFirst()
                .orElseThrow();
        assertThat(article.foreignKeyColumns())
                .as("ArticleMapper must rewrite the CLUB FK on operating_club_id")
                .containsExactly(new ForeignKeyColumn("operating_club_id", EntityType.CLUB));
    }


    private static final List<String> DELIVERY_LEGACY_COLUMNS = List.of(
            "DeliveryId", "ClubId", "ResolvedProcessStateId", "FlightId",
            "RecipientPersonId", "RecipientName", "RecipientFirstname",
            "RecipientLastname", "RecipientAddressLine1", "RecipientAddressLine2",
            "RecipientZipCode", "RecipientCity", "RecipientCountryName",
            "RecipientPersonClubMemberNumber", "DeliveryInformation",
            "AdditionalInformation", "DeliveryNumber", "DeliveredOn", "BatchId",
            "CreatedOn", "CreatedByUserId", "ModifiedOn", "ModifiedByUserId",
            "DeletedOn", "DeletedByUserId");

    private static final List<String> DELIVERY_ITEM_LEGACY_COLUMNS = List.of(
            "DeliveryItemId", "OperatingClubId", "DeliveryId", "Position",
            "ResolvedArticleId", "ArticleNumber", "ItemText", "AdditionalInformation",
            "Quantity", "ResolvedUnitPrice", "DiscountInPercent", "UnitType",
            "CreatedOn", "CreatedByUserId", "ModifiedOn", "ModifiedByUserId",
            "DeletedOn", "DeletedByUserId");

    @Test
    void deliveryAndItemAreRegisteredTenantScopedFullPort() {
        assertThat(MapperLegacyBindings.isRegistered(EntityType.DELIVERY))
                .as("DELIVERY must be bound (J-10b T-05) so the delivery register migrates")
                .isTrue();
        assertThat(MapperLegacyBindings.isRegistered(EntityType.DELIVERY_ITEM))
                .as("DELIVERY_ITEM must be bound — it is the Delivery aggregate child")
                .isTrue();
        assertThat(MapperLegacyBindings.portPolicy(EntityType.DELIVERY))
                .isEqualTo(MapperLegacyBindings.PortPolicy.FULL_PORT);
        assertThat(MapperLegacyBindings.portPolicy(EntityType.DELIVERY_ITEM))
                .isEqualTo(MapperLegacyBindings.PortPolicy.FULL_PORT);
    }

    @Test
    void deliverySelectProjectsEveryColumnTheMapperReads() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.DELIVERY);
        for (String legacyColumn : DELIVERY_LEGACY_COLUMNS) {
            assertThat(select)
                    .as("DeliveryMapper.writeNdjson reads %s — the bound SELECT must "
                            + "project it (else: silent NULL / export abort)", legacyColumn)
                    .contains(legacyColumn);
        }
        assertThat(select)
                .as("base table is the legacy Deliveries table")
                .contains("Deliveries");
    }

    @Test
    void deliverySelectDerivesProcessStateFromIsFurtherProcessedThenFlightState() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.DELIVERY)
                .toUpperCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", " ");
        assertThat(select)
                .as("IsFurtherProcessed=1 short-circuits to Booked (20) before any "
                        + "flight-state inspection")
                .contains("CASE WHEN D.ISFURTHERPROCESSED = 1 THEN 20");
        assertThat(select)
                .as("the flight-state JOIN must be a LEFT JOIN so FlightId-NULL "
                        + "deliveries survive and fall through to the Prepared floor")
                .contains("LEFT JOIN FLIGHTS");
        assertThat(select)
                .as("resolved state is aliased AS ResolvedProcessStateId")
                .contains("AS RESOLVEDPROCESSSTATEID");
    }

    @Test
    void deliverySelectCoalescesNullBatchIdToZero() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.DELIVERY)
                .toUpperCase(java.util.Locale.ROOT);
        assertThat(select)
                .as("NULL legacy BatchId must coalesce to 0 for the NOT-NULL column")
                .contains("COALESCE(D.BATCHID, 0)");
    }

    @Test
    void deliveryConsumerInsertTargetsTDeliveryWithOperatingClubId() {
        String insert = MapperLegacyBindings.insertForConsumer(EntityType.DELIVERY);
        assertThat(insert).contains("INSERT INTO t_delivery");
        assertThat(insert.toLowerCase(java.util.Locale.ROOT))
                .as("t_delivery is tenant-scoped via operating_club_id (V4)")
                .contains("operating_club_id");
    }

    @Test
    void deliveryItemSelectResolvesArticleByLiveArticleAndKeepsOrphansNull() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.DELIVERY_ITEM);
        for (String legacyColumn : DELIVERY_ITEM_LEGACY_COLUMNS) {
            assertThat(select)
                    .as("DeliveryItemMapper.writeNdjson reads %s — the bound SELECT "
                            + "must project it", legacyColumn)
                    .contains(legacyColumn);
        }
        String upper = select.toUpperCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        assertThat(upper)
                .as("item tenant is the parent Delivery's club, aliased AS OperatingClubId")
                .contains("D.CLUBID AS OPERATINGCLUBID");
        assertThat(upper)
                .as("article resolution is a LEFT JOIN (orphan ArticleNumber → null "
                        + "article_id, NOT a 23503 bundle failure)")
                .contains("LEFT JOIN ARTICLES");
        assertThat(upper)
                .as("the Article JOIN keys on (ClubId, ArticleNumber) AND IsDeleted=0 "
                        + "to pin the single live article (no soft-delete fan-out)")
                .contains("A.CLUBID = D.CLUBID")
                .contains("A.ARTICLENUMBER = DI.ARTICLENUMBER")
                .contains("A.ISDELETED = 0");
        assertThat(upper).contains("AS RESOLVEDARTICLEID");
    }

    @Test
    void deliveryItemSelectEmitsZeroUnitPriceSinceLegacyHasNone() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.DELIVERY_ITEM)
                .toUpperCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        assertThat(select)
                .as("unit_price has no legacy source → literal 0")
                .contains("CAST(0 AS DECIMAL(12, 4)) AS RESOLVEDUNITPRICE");
    }

    @Test
    void deliveryItemConsumerInsertTargetsTDeliveryItem() {
        String insert = MapperLegacyBindings.insertForConsumer(EntityType.DELIVERY_ITEM);
        assertThat(insert).contains("INSERT INTO t_delivery_item");
        assertThat(insert.toLowerCase(java.util.Locale.ROOT))
                .as("the item carries the parent's tenant via operating_club_id")
                .contains("operating_club_id");
    }

    @Test
    void everyBoundMappersForeignKeyTargetsAreAlsoBound() {
        for (Mapper mapper : KnownMappers.all()) {
            EntityType entity = mapper.entityType();
            if (!MapperLegacyBindings.isRegistered(entity)) {
                continue;
            }
            for (EntityType fkTarget : mapper.foreignKeys()) {
                assertThat(MapperLegacyBindings.isRegistered(fkTarget))
                        .as("%s is bound but its FK target %s is NOT — authored-but-unwired "
                                + "(J-0c T-21 class): bind %s or the bundle resolves an FK "
                                + "against an id-map it never produces",
                                entity, fkTarget, fkTarget)
                        .isTrue();
            }
        }
    }
}
