package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.migration.bundle.flight.StartTypeMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Producer-binding contract for the SELECT half of {@link MapperLegacyBindings}
 * (the legacy-export side consumed by the {@code S-139} export jar +
 * {@code ProducerHarness}).
 *
 * <p>The load-bearing invariant the registry Javadoc promises is "a legacy
 * column rename is caught by a {@code SELECT} failure rather than a silent
 * {@code NULL}": every legacy ResultSet column a mapper's {@code writeNdjson}
 * reads must be projected by the bound SELECT. This test pins that for the
 * tenant-scoped FLIGHT-group pair LOCATION + INOUTBOUND_POINT (T-02c) — they
 * were unbound before, so the export jar threw "No legacy binding registered"
 * for them.
 *
 * <p>Verified against the legacy MSSQL FLSTest schema
 * ({@code flsserver/database/FLSTest/2 alter/2 Alter Database.sql} +
 * {@code DBUpdate_v1.8.0.sql}): {@code Locations} carries no own
 * {@code ClubId} — it is the fan-out partner Club aliased producer-side per
 * {@link ch.alpenflight.migration.bundle.flight.LocationMapper}'s contract,
 * so it is asserted as an alias rather than a base table column.
 */
class MapperLegacyBindingsTest {

    /**
     * Every legacy ResultSet column {@code LocationMapper.writeNdjson} reads.
     * {@code ClubId} is the fan-out partner (no base-table column) — projected
     * by the SELECT's join, aliased on the cursor.
     */
    private static final List<String> LOCATION_LEGACY_COLUMNS = List.of(
            "LocationId", "ClubId", "LocationName", "LocationShortName",
            "CountryId", "LocationTypeId", "IcaoCode", "Latitude", "Longitude",
            "Elevation", "ElevationUnitType", "RunwayDirection", "RunwayLength",
            "RunwayLengthUnitType", "AirportFrequency", "Description",
            "SortIndicator", "IsInboundRouteRequired", "IsOutboundRouteRequired",
            "IsFastEntryRecord", "CreatedOn", "CreatedByUserId", "ModifiedOn",
            "ModifiedByUserId", "DeletedOn", "DeletedByUserId");

    /**
     * Every legacy ResultSet column {@code InOutboundPointMapper.writeNdjson}
     * reads. {@code ClubId} is the child's own fan-out partner club (the child
     * fans out across its parent Location's partner set, exactly like LOCATION) —
     * no base-table column, projected via the SELECT's join, aliased on the cursor.
     */
    private static final List<String> INOUTBOUND_POINT_LEGACY_COLUMNS = List.of(
            "InOutboundPointId", "LocationId", "ClubId", "InOutboundPointName",
            "IsInboundPoint", "IsOutboundPoint", "CreatedOn", "CreatedByUserId",
            "ModifiedOn", "ModifiedByUserId", "DeletedOn", "DeletedByUserId");

    /**
     * Every legacy {@code Persons} ResultSet column {@code PersonMapper.writeNdjson}
     * reads (J-0c T-21). {@code Persons} is cross-tenant (no {@code ClubId}); its
     * only outgoing FK is the nullable {@code CountryId} → {@code Countries},
     * resolved via the SYSTEM_GLOBAL COUNTRY id-map (T-15 mechanism), so the
     * column is projected verbatim and {@code PersonMapper.foreignKeys()} declares
     * COUNTRY. Note the British-spelled {@code LicenceNumber} (added by
     * DBUpdate_v1.8.1) and {@code Has*Licence}/{@code Has*StartPermission} flags
     * the later DBUpdate scripts add — the SELECT must project the names the
     * mapper actually reads, not the v1.0 base-schema names.
     */
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
        // Legacy Locations has no own ClubId; the producer fans out one row per
        // referencing Club (Clubs.HomebaseId + Flights start/ldg via OwnerId),
        // aliasing the partner Club id as the cursor's ClubId column.
        assertThat(select)
                .as("the fan-out partner Club must be aliased AS ClubId on the cursor")
                .contains("AS CLUBID");
    }

    @Test
    void locationSelectProjectsTheLocationTypeIntCupIdNotTheGuidFk() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.LOCATION).toUpperCase();
        // Legacy Locations.LocationTypeId is a uniqueidentifier (GUID FK to
        // LocationTypes), but LocationMapper.writeNdjson reads it via getInt +
        // legacyIntIdToUuidString — the legacy_int_id resolution expects the int
        // LocationTypeCupId (== t_location_type.legacy_int_id), which lives on
        // LocationTypes, not on Locations. The producer must JOIN LocationTypes
        // and project the int CupId AS LocationTypeId, else getInt throws
        // "conversion from uniqueidentifier to INTEGER is unsupported" (J-0c T-14).
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
        // The child fans out one row per (legacy IOP, partner club), joining its
        // parent Location's fan-out partner set — the SAME union the LOCATION
        // binding uses — and aliasing the partner Club id AS ClubId so the child
        // carries its OWN legacy club on the wire (resolver-only) for T-07's
        // composite (location_id, club_id) FK lookup.
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
        // PERSON is FULL_PORT, so it carries a real INSERT (unlike SYSTEM_GLOBAL
        // entries whose consumer half is empty). Cross-tenant: t_person has NO
        // club_id column, so the INSERT must not reference one.
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
        // Guard the fail-closed contract survives the registry growth: the negative
        // case points at a genuinely still-unbound EntityType — DELIVERY has an
        // authored mapper + KnownMappers entry but no MapperLegacyBindings entry yet.
        assertThatThrownBy(() -> MapperLegacyBindings.require(EntityType.DELIVERY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No legacy binding registered");
    }

    // -------------------------------------------------------------------------
    // AIRCRAFT + its two aggregate-internal children (J-1 T-04). The AIRCRAFT
    // mappers + KnownMappers entries already exist; T-04 wires the legacy binding
    // (producer SELECT + consumer INSERT) so the export jar / ProducerHarness stop
    // throwing "No legacy binding registered" for the Aircraft register.
    // -------------------------------------------------------------------------

    /**
     * Every legacy {@code Aircrafts} ResultSet column {@code AircraftMapper.writeNdjson}
     * reads. {@code ManagingClubId} is the producer-computed tenant identity aliased
     * on the cursor (cascade from {@code AircraftOwnerClubId} per the J-1 parity
     * decision); {@code AircraftType} is the real legacy int FK column the mapper's
     * {@code getInt} reads — the EF entity exposes it as the C# property
     * {@code AircraftTypeId} via {@code [Column("AircraftType")]}, but the MSSQL
     * column is {@code AircraftType} (T-16: the live export aborted on the prior
     * {@code AircraftTypeId} alias, which names a non-existent column).
     */
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

    /** Every legacy column {@code AircraftAircraftStateMapper.writeNdjson} reads. */
    private static final List<String> AIRCRAFT_AIRCRAFT_STATE_LEGACY_COLUMNS = List.of(
            "AircraftId", "AircraftState", "ValidFrom", "ValidTo", "NoticedByPersonId",
            "Remarks", "CreatedOn", "CreatedByUserId", "ModifiedOn", "ModifiedByUserId",
            "DeletedOn", "DeletedByUserId");

    /** Every legacy column {@code AircraftOperatingCounterMapper.writeNdjson} reads. */
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
        // J-1 parity decision: managing_club_id is derived from legacy
        // AircraftOwnerClubId (the authored AircraftMapper cascade) and aliased
        // AS ManagingClubId on the cursor; the mapper reads it verbatim.
        assertThat(select)
                .as("managing_club_id source must be AircraftOwnerClubId, aliased AS ManagingClubId")
                .contains("AIRCRAFTOWNERCLUBID AS MANAGINGCLUBID");
    }

    @Test
    void aircraftSelectProjectsTheRealAircraftTypeColumn() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.AIRCRAFT).toUpperCase();
        // AircraftMapper reads getInt("AircraftType") — the int code resolved via
        // legacyIntIdToUuidString. The real legacy column is AircraftType (Aircrafts
        // DDL; EF Aircraft.cs [Column("AircraftType")]), NOT AircraftTypeId — the
        // export aborts on a non-existent column (T-16). The SELECT must project the
        // real column and must NOT reference the C#-property name AircraftTypeId.
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
        // Real legacy column is AircraftState (AircraftAircraftStates DDL; EF
        // AircraftAircraftState.cs [Column("AircraftState")]), NOT AircraftStateId.
        // Same class of bug as AIRCRAFT's AircraftType (T-16).
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

    /**
     * Binding-completeness closure guard (J-1 T-04 boyscout, the J-0c T-21
     * "authored-but-unwired FK target" class). For every entity that HAS a
     * {@link MapperLegacyBindings} entry, its mapper's {@link Mapper#foreignKeys()}
     * targets must ALSO have a binding — otherwise the producer/consumer resolves
     * an FK against an id-map that the bundle never produces, surfacing as a
     * ProducerHarness "No legacy binding registered" mid-run instead of here.
     *
     * <p>Scoped to the bound set's closure (not every KnownMapper): the registry
     * grows journey-by-journey, so an authored-but-not-yet-bound mapper (e.g.
     * FLIGHT) legitimately points at targets that land later — what must never
     * happen is a BOUND mapper pointing at an UNBOUND target.
     */
    // -------------------------------------------------------------------------
    // FLIGHT + FLIGHT_CREW + their reference closure START_TYPE / FLIGHT_TYPE
    // (J-2 T-07). The mappers + KnownMappers entries already exist (J-0b
    // authoring) but were UNBOUND; T-07 wires the producer SELECT (reconciled
    // against the real legacy Flights/FlightCrew MSSQL schema) + the consumer
    // INSERT so the Flight register migrates end-to-end. The closure guard
    // (everyBoundMappersForeignKeyTargetsAreAlsoBound) forces START_TYPE +
    // FLIGHT_TYPE to be bound the moment FLIGHT is: FlightMapper.foreignKeys()
    // lists them, and FlightCrewMapper lists FLIGHT + PERSON.
    // -------------------------------------------------------------------------

    /**
     * Every legacy {@code Flights} ResultSet column {@code FlightMapper.writeNdjson}
     * reads. The producer-SELECT catch (J-1 T-16 class,
     * {@code project_synth_bundle_doesnt_validate_producer_select}): the
     * operating-club source is the real legacy {@code OwnerId} column
     * ({@code Flight.cs:130}; same column the LOCATION fan-out keys
     * {@code Flights.OwnerId AS ClubId}), NOT {@code OwnerClubId} — that column
     * does not exist on the legacy {@code Flights} table and would abort the live
     * export. {@code StartType} / {@code FlightCostBalanceType} are the real int
     * columns (EF {@code [Column("StartType")]} / {@code [Column("FlightCostBalanceType")]}
     * over the C# {@code StartTypeId} / {@code FlightCostBalanceTypeId} properties).
     * {@code FlightAircraftType} / {@code AirStateId} / {@code ProcessStateId} /
     * {@code FlightDate} / {@code NrOfLdgsOnStartLocation} / the
     * {@code EngineStartOperatingCounterInSeconds} pair are all added by the
     * DBUpdate chain (v1.9.x) and exist in the final FLSTest schema.
     */
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

    /** Every legacy {@code FlightCrew} ResultSet column the mapper reads. */
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
        // The producer-SELECT catch (J-1 T-16 class): legacy Flights has an OwnerId
        // column (the ASP.NET ownership club, = the operating club per
        // FlightReportService.cs:123 / the LOCATION fan-out key), and NO OwnerClubId
        // column. FlightMapper.writeNdjson originally read getString("OwnerClubId"),
        // which would abort the live export on a non-existent column.
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

    /**
     * START_TYPE SYSTEM_GLOBAL completeness guard (J-2 T-39, the J-1 T-16 class).
     * A FLIGHT's {@code start_type_id} carries the synthetic
     * {@code UUID(0, legacyAircraftStartType)} for ANY legacy enum value — the
     * legacy {@code AircraftStartType} enum is 1=TowingByAircraft, 2=WinchLaunch,
     * 3=SelfStart, 4=ExternalStart, 5=MotorFlightStart, and the column can hold
     * any of them regardless of which rows the legacy {@code StartTypes} table
     * seeds. So the bundle's {@code legacy_id_map_START_TYPE} closure must
     * enumerate ALL FIVE — a missing value (the real FLSTest SelfStart(3) catch)
     * fail-closes the ingest with {@code BUNDLE_CROSS_TENANT_FK_LEAK}. This guard
     * fails loudly at build, not at the nightly real-bundle ingest.
     */
    @Test
    void startTypeClosureEnumeratesTheFullLegacyAircraftStartTypeEnum() {
        Map<UUID, UUID> closure = StartTypeMapper.legacyEnumIdToSeedPk();

        // The legacy AircraftStartType enum is exactly 1..5 (no gaps, no extras):
        // flsserver/src/FLS.Server.Data/Enums/AircraftStartType.cs.
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

        // SelfStart(3) is the value the real export caught — pin it explicitly.
        assertThat(closure)
                .as("SelfStart(3) must map to the V2 SELF_START seed PK")
                .containsEntry(new UUID(0L, 3L), SeedReferenceUuids.startTypeByCode("SELF_START"));

        // The 5 enum values map to 5 DISTINCT seed PKs (no accidental collapse).
        assertThat(closure.values().stream().distinct().count())
                .as("the 5 legacy start types map to 5 distinct t_start_type seed PKs")
                .isEqualTo(5L);
    }

    // -------------------------------------------------------------------------
    // AIRCRAFT_RESERVATION + AIRCRAFT_RESERVATION_TYPE (J-5 T-07). The mappers +
    // KnownMappers entries + the TENANT_BYPASS_ALLOW_LIST entry already exist
    // (S-185/S-187 authoring) but were UNBOUND — MapperLegacyBindings.require()
    // threw "No legacy binding registered", so the producer never emitted the
    // reservation streams and the round-trip was authored-but-unproven
    // (verify_infra_is_run_not_just_authored). T-07 wires the producer SELECT
    // (reconciled against the REAL legacy AircraftReservations / -Types MSSQL
    // schema as it stands AFTER DBUpdate_v1.9.23 — which DROPS the v1.0
    // ReservationTypeId + InstructorPersonId columns and ADDS
    // AircraftReservationTypeId / FlightTypeId / SecondCrewPersonId) + the
    // consumer INSERT. The closure guard below forces every reservation FK
    // target (CLUB / AIRCRAFT / PERSON / LOCATION / AIRCRAFT_RESERVATION_TYPE /
    // FLIGHT_TYPE) to be bound the moment AIRCRAFT_RESERVATION is.
    //
    // Producer-SELECT catch (project_synth_bundle_doesnt_validate_producer_select):
    // the modern legacy column is AircraftReservationTypeId, NOT the v1.0
    // ReservationTypeId (dropped in v1.9.23); the second-crew column is
    // SecondCrewPersonId, NOT InstructorPersonId (also dropped in v1.9.23). A
    // SELECT naming either dropped column would abort the live export — these
    // guards pin the post-v1.9.23 names the mapper's writeNdjson actually reads.
    // -------------------------------------------------------------------------

    /**
     * Every legacy {@code AircraftReservations} ResultSet column
     * {@code AircraftReservationMapper.writeNdjson} reads, in the post-v1.9.23
     * schema. {@code ClubId} (the @TenantId source) + {@code LocationId} are
     * added by DBUpdate_v1.1; {@code AircraftReservationTypeId} /
     * {@code FlightTypeId} / {@code SecondCrewPersonId} by DBUpdate_v1.9.23.
     */
    private static final List<String> AIRCRAFT_RESERVATION_LEGACY_COLUMNS = List.of(
            "AircraftReservationId", "ClubId", "AircraftId", "Start", "End",
            "IsAllDayReservation", "PilotPersonId", "SecondCrewPersonId", "LocationId",
            "AircraftReservationTypeId", "FlightTypeId", "Remarks",
            "CreatedOn", "CreatedByUserId", "ModifiedOn", "ModifiedByUserId",
            "DeletedOn", "DeletedByUserId");

    /** Every legacy column {@code AircraftReservationTypeMapper.writeNdjson} reads (post-v1.9.23). */
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
        // DBUpdate_v1.9.23 DROPs ReservationTypeId (replaced by
        // AircraftReservationTypeId) and InstructorPersonId (replaced by
        // SecondCrewPersonId). A SELECT naming either dropped column aborts the
        // live export on a non-existent column (the J-1 T-16 producer-SELECT class).
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

    // -------------------------------------------------------------------------
    // PLANNING_DAY + PLANNING_DAY_ASSIGNMENT + PLANNING_DAY_ASSIGNMENT_TYPE
    // (J-6 T-11). The 3 mappers + KnownMappers entries already existed (S-014
    // baseline authoring, unit-tested) but were UNBOUND — MapperLegacyBindings.
    // require() threw "No legacy binding registered", so the producer never
    // emitted the planning streams and the round-trip was authored-but-unproven
    // (verify_infra_is_run_not_just_authored). T-11 wires the producer SELECT
    // (reconciled against the REAL legacy PlanningDays / PlanningDayAssignments /
    // PlanningDayAssignmentTypes MSSQL schema, DBUpdate_v1.0.1 DDL) + the consumer
    // INSERT; PlanningDayMigrationRoundTripIT (server) proves the fan-out-resolved
    // location round-trip end to end.
    //
    // Producer-SELECT catches (project_synth_bundle_doesnt_validate_producer_select):
    //  * the type mapper reads getInt("RequiredNrOfAssignments"), but the real
    //    legacy column is RequiredNrOfPlanningDayAssignments (no [Column] rename on
    //    the EF entity) — projected AS RequiredNrOfAssignments, else export abort.
    //  * the assignment table has NO own ClubId column — operating_club_id is
    //    denormalised by JOINing the parent PlanningDays and projecting its ClubId
    //    AS OperatingClubId (the name the mapper reads).
    // -------------------------------------------------------------------------

    /** Every legacy {@code PlanningDays} column {@code PlanningDayMapper.writeNdjson} reads. */
    private static final List<String> PLANNING_DAY_LEGACY_COLUMNS = List.of(
            "PlanningDayId", "ClubId", "Day", "LocationId", "Remarks",
            "CreatedOn", "CreatedByUserId", "ModifiedOn", "ModifiedByUserId",
            "DeletedOn", "DeletedByUserId");

    /**
     * Every legacy column {@code PlanningDayAssignmentMapper.writeNdjson} reads.
     * {@code OperatingClubId} is the denormalised @TenantId — sourced producer-side
     * by JOINing PlanningDays (the assignment table has no own ClubId), aliased on
     * the cursor.
     */
    private static final List<String> PLANNING_DAY_ASSIGNMENT_LEGACY_COLUMNS = List.of(
            "PlanningDayAssignmentId", "OperatingClubId", "AssignedPlanningDayId",
            "AssignedPersonId", "AssignmentTypeId", "Remarks",
            "CreatedOn", "CreatedByUserId", "ModifiedOn", "ModifiedByUserId",
            "DeletedOn", "DeletedByUserId");

    /** Every legacy column {@code PlanningDayAssignmentTypeMapper.writeNdjson} reads. */
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
        // J-6 T-11b: the real legacy PlanningDays has duplicate
        // (ClubId, Day, LocationId) rows (no legacy UNIQUE), but V4's
        // ux_pln_club_date_loc partial unique forbids them. PLANNING_DAY is NOT
        // fan-out, so two dups resolve to the same club + own-club Location
        // replica → the 2nd INSERT 23505s (the §4 fanout gate blocker). The
        // producer SELECT must keep-first per the UNIQUE-partial key, ordered
        // deterministically (CreatedOn, PlanningDayId), so only one row reaches
        // the mapper. This pins the dedupe so a future edit can't drop it and
        // silently reintroduce the 23505. (The behavioural keep-first proof —
        // two seeded dups, one survivor — runs against real MSSQL in the parity
        // oracle harness; the round-trip IT proves the deduped output ingests.)
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
        // The real legacy PlanningDayAssignments table has NO own ClubId column —
        // operating_club_id is denormalised by JOINing PlanningDays and projecting
        // its ClubId AS OperatingClubId (the name the mapper reads). A SELECT that
        // tried to read PlanningDayAssignments.ClubId would abort the live export.
        assertThat(upper)
                .as("operating_club_id is sourced by JOINing the parent PlanningDays")
                .contains("JOIN PLANNINGDAYS");
        assertThat(upper)
                .as("the parent's ClubId is aliased AS OperatingClubId on the cursor")
                .contains("AS OPERATINGCLUBID");
        // J-6 T-16 (the 23503 fix): the PLANNING_DAY SELECT keeps-first per
        // (ClubId, Day, LocationId) and DROPS duplicate days, so an assignment of a
        // dropped day would FK-violate (fk_pda_planning_day_id, 23503) at ingest.
        // The assignment SELECT must REMAP planning_day_id onto the SURVIVING
        // (kept-first) day for its parent's (ClubId, Day, LocationId) — the same
        // FIRST_VALUE/ROW_NUMBER keep-first ordering — so every exported assignment
        // points at a day that WILL exist post-dedupe. Pin it so a future edit can't
        // drop the remap and silently reintroduce the 23503.
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
        // The mapper reads getInt("RequiredNrOfAssignments"), but the real MSSQL
        // column is RequiredNrOfPlanningDayAssignments (DBUpdate_v1.0.1 DDL; the EF
        // entity property of that name, no [Column] rename). The SELECT must project
        // the real column AS the short name the mapper reads — else the live export
        // aborts on a non-existent RequiredNrOfAssignments column (the J-1 T-16
        // producer-SELECT class).
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
                .as("ARTICLE must be bound (J-11 T-08) so the article register exports — "
                        + "the J-10b DeliveryItem.article_id RESTRICT done-bar")
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
        // operating_club_id is off-convention for the CLUB target (the resolver's
        // default derives club_id). Without the foreignKeyColumns() override the
        // legacy ClubId GUID reaches the INSERT verbatim → fk_article_operating_club_id
        // 23503 for every article at the §4 fanout.
        Mapper article = KnownMappers.all().stream()
                .filter(m -> m.entityType() == EntityType.ARTICLE)
                .findFirst()
                .orElseThrow();
        assertThat(article.foreignKeyColumns())
                .as("ArticleMapper must rewrite the CLUB FK on operating_club_id")
                .containsExactly(new ForeignKeyColumn("operating_club_id", EntityType.CLUB));
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
