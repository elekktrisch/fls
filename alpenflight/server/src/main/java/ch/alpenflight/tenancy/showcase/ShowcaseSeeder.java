package ch.alpenflight.tenancy.showcase;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftStateHistoryEntry;
import ch.alpenflight.flights.application.FlightReportRebuildService;
import ch.alpenflight.flights.application.FlightStateTransitionService;
import ch.alpenflight.flights.domain.CrewMemberSpec;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightAircraftType;
import ch.alpenflight.flights.domain.FlightCrewTypeIds;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.TransitionTrigger;
import ch.alpenflight.locations.domain.Location;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.tenancy.provisioning.application.ReferenceDataSeeder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ShowcaseSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(ShowcaseSeeder.class);


    static final UUID CLUB_1 = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    static final UUID CLUB_2 = UUID.fromString("019e30c3-2c00-7001-8000-000000000002");

    private static final UUID COUNTRY_CH = UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID CLUB_STATE_ACTIVE = UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID LANGUAGE_DE = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");

    private static final UUID LOC_TYPE_GLIDER_AIRFIELD =
            UUID.fromString("019e2e15-2c00-72cb-8000-0000000032cb");
    private static final UUID LOC_TYPE_CONCRETE_RUNWAY =
            UUID.fromString("019e2e15-2c00-72cc-8000-0000000032cc");

    private static final UUID AC_TYPE_GLIDER =
            UUID.fromString("019e2e15-2c00-7af9-8000-000000002af9");
    private static final UUID AC_TYPE_MOTOR_GLIDER =
            UUID.fromString("019e2e15-2c00-7afb-8000-000000002afb");
    private static final UUID AC_TYPE_MOTOR_AIRCRAFT =
            UUID.fromString("019e2e15-2c00-7afc-8000-000000002afc");

    private static final UUID AC_STATE_OK =
            UUID.fromString("019e2e15-2c00-7ee0-8000-000000002ee0");


    public static final UUID LOCATION_C1_HOME =
            UUID.fromString("019e30c3-2c00-7301-8000-000000000301");
    public static final UUID LOCATION_C1_DEST_1 =
            UUID.fromString("019e30c3-2c00-7301-8000-000000000302");
    public static final UUID LOCATION_C1_DEST_2 =
            UUID.fromString("019e30c3-2c00-7301-8000-000000000303");
    public static final UUID LOCATION_C2_HOME =
            UUID.fromString("019e30c3-2c00-7301-8000-000000000304");
    public static final UUID LOCATION_C2_DEST_1 =
            UUID.fromString("019e30c3-2c00-7301-8000-000000000305");
    public static final UUID LOCATION_C2_DEST_2 =
            UUID.fromString("019e30c3-2c00-7301-8000-000000000306");

    public static final UUID AIRCRAFT_C1_GLIDER =
            UUID.fromString("019e30c3-2c00-7401-8000-000000000401");
    public static final UUID AIRCRAFT_C1_TOW =
            UUID.fromString("019e30c3-2c00-7401-8000-000000000402");
    public static final UUID AIRCRAFT_C1_MOTOR =
            UUID.fromString("019e30c3-2c00-7401-8000-000000000403");
    public static final UUID AIRCRAFT_C2_GLIDER =
            UUID.fromString("019e30c3-2c00-7401-8000-000000000404");
    public static final UUID AIRCRAFT_CHARTER_C2 =
            UUID.fromString("019e30c3-2c00-7401-8000-000000000405");

    private static final Instant AIRWORTHY_SINCE =
            Instant.parse("2024-01-01T00:00:00Z");

    private static final UUID START_TYPE_WINCH =
            UUID.fromString("019e2e15-2c00-7fa0-8000-000000000fa0");
    private static final UUID START_TYPE_AEROTOW =
            UUID.fromString("019e2e15-2c00-7fa1-8000-000000000fa1");
    private static final UUID START_TYPE_MOTOR =
            UUID.fromString("019e2e15-2c00-7fa2-8000-000000000fa2");


    private static final UUID PERSON_PILOT1 =
            UUID.fromString("019e30c3-2c00-7601-8000-000000000601");
    private static final UUID PERSON_PILOT_C2 =
            UUID.fromString("019e30c3-2c00-7601-8000-000000000602");
    private static final UUID PERSON_CLUB_PILOT1 =
            UUID.fromString("019e30c3-2c00-7701-8000-000000000701");

    private static final UUID USER_PILOT1 =
            UUID.fromString("019e30c3-2c00-7100-8000-000000000002");
    private static final UUID USER_PILOT_C2 =
            UUID.fromString("019e30c3-2c00-7100-8000-000000000022");

    private record ShowcasePrincipal(
            UUID userId, UUID keycloakSub, UUID clubId, String username, String friendlyName) {}

    private static final List<ShowcasePrincipal> PRINCIPALS = List.of(
            new ShowcasePrincipal(
                    UUID.fromString("019e30c3-2c00-7100-8000-000000000020"),
                    UUID.fromString("019e30c3-2c00-7200-8000-000000000020"),
                    CLUB_1, "pilot-empty1", "Pilot Empty One"),
            new ShowcasePrincipal(
                    UUID.fromString("019e30c3-2c00-7100-8000-000000000021"),
                    UUID.fromString("019e30c3-2c00-7200-8000-000000000021"),
                    CLUB_2, "clubadmin-c2", "Club Admin Two-Club"),
            new ShowcasePrincipal(
                    UUID.fromString("019e30c3-2c00-7100-8000-000000000022"),
                    UUID.fromString("019e30c3-2c00-7200-8000-000000000022"),
                    CLUB_2, "pilot-c2", "Pilot Two-Club"));

    private final JdbcTemplate jdbc;
    private final ReferenceDataSeeder referenceDataSeeder;
    private final FlightStateTransitionService flightTransitions;
    private final FlightReportRebuildService flightReportRebuild;
    private final TransactionTemplate txTemplate;

    public ShowcaseSeeder(JdbcTemplate jdbc,
                          ReferenceDataSeeder referenceDataSeeder,
                          FlightStateTransitionService flightTransitions,
                          FlightReportRebuildService flightReportRebuild,
                          PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.referenceDataSeeder = referenceDataSeeder;
        this.flightTransitions = flightTransitions;
        this.flightReportRebuild = flightReportRebuild;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    public void seed() {
        LOG.info("showcase-seed: loading tenancy + principals (idempotent upserts) ...");

        txTemplate.executeWithoutResult(status -> {
            seedShowcaseClub();
            referenceDataSeeder.seedDefaults(CLUB_1);
            referenceDataSeeder.seedDefaults(CLUB_2);
            seedPrincipals();
            seedLocations();
            seedAircraft();
            seedPersonsAndLinks();
            seedFlightBaseRows();
        });

        driveFlightTransitions();

        flightReportRebuild.rebuildForClub(CLUB_1);
        flightReportRebuild.rebuildForClub(CLUB_2);

        LOG.info("showcase-seed: done — clubs=[seed-club-1, showcase-club-2], "
                + "reference-data seeded per club, {} net-new principal(s): {}, "
                + "6 locations (3/club) + 5 aircraft (glider/tow/TMG/charter), "
                + "14 flights (8 club-1 + 6 club-2; aerotow/winch/motor variants across "
                + "NotProcessed/Valid/Invalid/Locked/DeliveryBooked via real domain transitions)",
                PRINCIPALS.size(),
                PRINCIPALS.stream().map(ShowcasePrincipal::username).toList());
    }

    private void seedShowcaseClub() {
        jdbc.update("""
                INSERT INTO t_club (id, clubname, club_key, country_id, club_state_id,
                        slug, public_registration_enabled)
                VALUES (?::uuid, ?, ?, ?::uuid, ?::uuid, ?, false)
                ON CONFLICT (id) DO NOTHING
                """,
                CLUB_2.toString(), "Showcase Club Two", "SHOW2",
                COUNTRY_CH.toString(), CLUB_STATE_ACTIVE.toString(), "showcase-club-2");
    }

    private void seedPrincipals() {
        for (ShowcasePrincipal p : PRINCIPALS) {
            jdbc.update("""
                    INSERT INTO t_user (id, club_id, username, friendly_name,
                            notification_email, language_id, keycloak_sub)
                    VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                    ON CONFLICT (id) DO NOTHING
                    """,
                    p.userId().toString(), p.clubId().toString(), p.username(),
                    p.friendlyName(), p.username() + "@example.com",
                    LANGUAGE_DE.toString(), p.keycloakSub().toString());
        }
    }


    private void seedLocations() {
        Tenants.runAs(CLUB_1, () -> {
            insertLocation(LOCATION_C1_HOME, CLUB_1,
                    "Showcase Airfield One", "SHOW1", LOC_TYPE_GLIDER_AIRFIELD, "LSZX");
            insertLocation(LOCATION_C1_DEST_1, CLUB_1,
                    "Bern-Belp (showcase)", "BERN", LOC_TYPE_CONCRETE_RUNWAY, "LSGB");
            insertLocation(LOCATION_C1_DEST_2, CLUB_1,
                    "Saanen (showcase)", "SAAN", LOC_TYPE_CONCRETE_RUNWAY, "LSPD");
        });
        Tenants.runAs(CLUB_2, () -> {
            insertLocation(LOCATION_C2_HOME, CLUB_2,
                    "Showcase Airfield Two", "SHOW2", LOC_TYPE_GLIDER_AIRFIELD, "LSZW");
            insertLocation(LOCATION_C2_DEST_1, CLUB_2,
                    "Gruyeres (showcase)", "GRUY", LOC_TYPE_CONCRETE_RUNWAY, "LSGT");
            insertLocation(LOCATION_C2_DEST_2, CLUB_2,
                    "Montricher (showcase)", "MONT", LOC_TYPE_CONCRETE_RUNWAY, "LSPM");
        });
    }

    private void insertLocation(UUID id, UUID clubId, String name, String shortName,
                                UUID locationTypeId, String icao) {
        Location loc = Location.create(
                name, shortName, COUNTRY_CH, locationTypeId, icao,
                null, null, null, null, null, null, null, null, null, null,
                false, false, false);
        jdbc.update("""
                INSERT INTO t_location (id, club_id, location_name, location_short_name,
                        country_id, location_type_id, icao_code,
                        is_inbound_route_required, is_outbound_route_required, is_fast_entry_record)
                VALUES (?::uuid, ?::uuid, ?, ?, ?::uuid, ?::uuid, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                id.toString(), clubId.toString(), loc.getLocationName(),
                loc.getLocationShortName(), reqUuid(loc.getCountryId()),
                reqUuid(loc.getLocationTypeId()), loc.getIcaoCode(),
                loc.isInboundRouteRequired(), loc.isOutboundRouteRequired(),
                loc.isFastEntryRecord());
    }


    private void seedAircraft() {
        insertAircraft(AIRCRAFT_C1_GLIDER, CLUB_1, AC_TYPE_GLIDER, "HB-3001",
                "Schleicher", "ASK 21", "X1", false);
        insertAircraft(AIRCRAFT_C1_TOW, CLUB_1, AC_TYPE_MOTOR_AIRCRAFT, "HB-TOW1",
                "Robin", "DR400", null, true);
        insertAircraft(AIRCRAFT_C1_MOTOR, CLUB_1, AC_TYPE_MOTOR_GLIDER, "HB-MOT1",
                "Diamond", "HK36 TTC", null, false);
        insertAircraft(AIRCRAFT_C2_GLIDER, CLUB_2, AC_TYPE_GLIDER, "HB-3002",
                "Schleicher", "ASK 21", "X2", false);
        insertAircraft(AIRCRAFT_CHARTER_C2, CLUB_2, AC_TYPE_MOTOR_AIRCRAFT, "HB-CHTR",
                "Cessna", "C172", null, false);
    }

    private void insertAircraft(UUID id, UUID managingClubId, UUID aircraftTypeId,
                                String immatriculation, String manufacturer,
                                String model, @Nullable String competitionSign,
                                boolean towingAircraft) {
        Aircraft a = Aircraft.register(
                managingClubId, managingClubId, aircraftTypeId, immatriculation,
                manufacturer, model, competitionSign, null, null, null, null, null,
                null, null, null, null, null, null, null,
                false, false, false, towingAircraft, null, null);
        AircraftStateHistoryEntry state = a.changeState(AC_STATE_OK, AIRWORTHY_SINCE, null, null);

        jdbc.update("""
                INSERT INTO t_aircraft (id, managing_club_id, owner_club_id, aircraft_type_id,
                        manufacturer_name, aircraft_model, immatriculation, competition_sign,
                        is_towing_or_winch_required, is_towing_start_allowed,
                        is_winch_start_allowed, is_towing_aircraft, is_fast_entry_record)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                id.toString(), managingClubId.toString(), managingClubId.toString(),
                reqUuid(a.getAircraftTypeId()), a.getManufacturerName(), a.getAircraftModel(),
                a.getImmatriculation(), a.getCompetitionSign(),
                a.isTowingOrWinchRequired(), a.isTowingStartAllowed(),
                a.isWinchStartAllowed(), a.isTowingAircraft(), a.isFastEntryRecord());

        jdbc.update("""
                INSERT INTO t_aircraft_aircraft_state (id, aircraft_id, aircraft_state_id,
                        valid_from, valid_to)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                stateRowId(id).toString(), id.toString(), reqUuid(state.getAircraftStateId()),
                java.sql.Timestamp.from(reqInstant(state.getValidFrom())), null);
    }

    private static UUID stateRowId(UUID aircraftId) {
        return UUID.fromString(
                aircraftId.toString().replaceFirst("-7401-", "-7501-"));
    }


    private void seedPersonsAndLinks() {
        insertPilot1Person();
        insertPerson(PERSON_PILOT_C2, "Two-Club", "Pilot");
        insertPilot1PersonClub();
        linkUserPerson(USER_PILOT1, PERSON_PILOT1);
        linkUserPerson(USER_PILOT_C2, PERSON_PILOT_C2);
        jdbc.update(
                "UPDATE t_user SET phone_number = ? WHERE id = ?::uuid AND phone_number IS NULL",
                "+41 79 000 00 01", USER_PILOT1.toString());
    }

    private void insertPerson(UUID id, String lastname, String firstname) {
        jdbc.update("""
                INSERT INTO t_person (id, lastname, firstname, country_id)
                VALUES (?::uuid, ?, ?, ?::uuid)
                ON CONFLICT (id) DO NOTHING
                """,
                id.toString(), lastname, firstname, COUNTRY_CH.toString());
    }

    private void insertPilot1Person() {
        jdbc.update("""
                INSERT INTO t_person (
                    id, lastname, firstname,
                    address_line1, zip, city, region, country_id,
                    private_phone, mobile_phone, business_phone,
                    email_private, email_business, prefer_mail_to_business_mail,
                    birthday, enable_address,
                    has_glider_pilot_licence, licence_number, medical_class2_expire_date
                ) VALUES (
                    ?::uuid, 'One', 'Pilot',
                    'Flugplatzstrasse 1', '3000', 'Bern', 'BE', ?::uuid,
                    '+41 31 000 00 01', '+41 79 000 00 01', '+41 31 000 00 02',
                    'pilot1.private@example.com', 'pilot1.business@example.com', false,
                    DATE '1985-06-15', true,
                    true, 'CH-GLD-0001', DATE '2027-09-30'
                )
                ON CONFLICT (id) DO NOTHING
                """,
                PERSON_PILOT1.toString(), COUNTRY_CH.toString());
    }

    private void insertPilot1PersonClub() {
        jdbc.update("""
                INSERT INTO t_person_club (
                    id, person_id, club_id, member_number,
                    is_glider_pilot,
                    receive_flight_reports,
                    receive_aircraft_reservation_notifications,
                    receive_planning_day_role_reminder,
                    is_active
                ) VALUES (
                    ?::uuid, ?::uuid, ?::uuid, 'M-0001',
                    true,
                    true,   -- receive_flight_reports
                    false,  -- receive_aircraft_reservation_notifications
                    true,   -- receive_planning_day_role_reminder
                    true
                )
                ON CONFLICT (id) DO NOTHING
                """,
                PERSON_CLUB_PILOT1.toString(), PERSON_PILOT1.toString(), CLUB_1.toString());
    }

    private void linkUserPerson(UUID userId, UUID personId) {
        jdbc.update(
                "UPDATE t_user SET person_id = ?::uuid WHERE id = ?::uuid AND person_id IS NULL",
                personId.toString(), userId.toString());
    }


    private record FlightSpec(UUID id,
                              UUID clubId,
                              FlightAircraftType type,
                              UUID aircraftId,
                              UUID picPersonId,
                              UUID startTypeId,
                              UUID homeLocationId,
                              int daysAgo,
                              FlightProcessState targetState,
                              @Nullable UUID towFlightId) {}

    private static List<FlightSpec> club1Matrix() {
        return List.of(
                glider(flightId(0x01), CLUB_1, AIRCRAFT_C1_GLIDER, PERSON_PILOT1,
                        START_TYPE_AEROTOW, LOCATION_C1_HOME, 0, FlightProcessState.NOT_PROCESSED, flightId(0x02)),
                tow(flightId(0x02), CLUB_1, AIRCRAFT_C1_TOW, PERSON_PILOT1,
                        LOCATION_C1_HOME, 0, FlightProcessState.NOT_PROCESSED),
                glider(flightId(0x03), CLUB_1, AIRCRAFT_C1_GLIDER, PERSON_PILOT1,
                        START_TYPE_WINCH, LOCATION_C1_HOME, 0, FlightProcessState.NOT_PROCESSED, null),
                motor(flightId(0x04), CLUB_1, AIRCRAFT_C1_MOTOR, PERSON_PILOT1,
                        LOCATION_C1_HOME, 5, FlightProcessState.VALID),
                glider(flightId(0x05), CLUB_1, AIRCRAFT_C1_GLIDER, PERSON_PILOT1,
                        START_TYPE_WINCH, LOCATION_C1_HOME, 6, FlightProcessState.INVALID, null),
                glider(flightId(0x06), CLUB_1, AIRCRAFT_C1_GLIDER, PERSON_PILOT1,
                        START_TYPE_AEROTOW, LOCATION_C1_HOME, 10, FlightProcessState.LOCKED, flightId(0x07)),
                tow(flightId(0x07), CLUB_1, AIRCRAFT_C1_TOW, PERSON_PILOT1,
                        LOCATION_C1_HOME, 10, FlightProcessState.LOCKED),
                motor(flightId(0x08), CLUB_1, AIRCRAFT_C1_MOTOR, PERSON_PILOT1,
                        LOCATION_C1_HOME, 20, FlightProcessState.DELIVERY_BOOKED));
    }

    private static List<FlightSpec> club2Matrix() {
        return List.of(
                glider(flightId(0x11), CLUB_2, AIRCRAFT_C2_GLIDER, PERSON_PILOT_C2,
                        START_TYPE_WINCH, LOCATION_C2_HOME, 0, FlightProcessState.NOT_PROCESSED, null),
                motor(flightId(0x12), CLUB_2, AIRCRAFT_CHARTER_C2, PERSON_PILOT_C2,
                        LOCATION_C2_HOME, 5, FlightProcessState.VALID),
                glider(flightId(0x13), CLUB_2, AIRCRAFT_C2_GLIDER, PERSON_PILOT_C2,
                        START_TYPE_WINCH, LOCATION_C2_HOME, 7, FlightProcessState.INVALID, null),
                glider(flightId(0x14), CLUB_2, AIRCRAFT_C2_GLIDER, PERSON_PILOT_C2,
                        START_TYPE_AEROTOW, LOCATION_C2_HOME, 12, FlightProcessState.LOCKED, flightId(0x15)),
                tow(flightId(0x15), CLUB_2, AIRCRAFT_CHARTER_C2, PERSON_PILOT_C2,
                        LOCATION_C2_HOME, 12, FlightProcessState.LOCKED),
                glider(flightId(0x16), CLUB_2, AIRCRAFT_C2_GLIDER, PERSON_PILOT_C2,
                        START_TYPE_WINCH, LOCATION_C2_HOME, 22, FlightProcessState.DELIVERY_BOOKED, null));
    }

    private void seedFlightBaseRows() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Tenants.runAs(CLUB_1, () -> insertClubFlightRows(club1Matrix(), today));
        Tenants.runAs(CLUB_2, () -> insertClubFlightRows(club2Matrix(), today));
    }

    private void insertClubFlightRows(List<FlightSpec> club, LocalDate today) {
        club.forEach(s -> insertFlightRow(s, today));
        club.forEach(s -> insertCrewRow(s, today));
        club.forEach(this::linkTowIfPaired);
    }

    private void driveFlightTransitions() {
        List<FlightSpec> club1 = club1Matrix();
        List<FlightSpec> club2 = club2Matrix();
        Tenants.runAs(CLUB_1, () ->
                club1.stream().filter(s -> !isCascadedTow(s, club1)).forEach(this::driveToState));
        Tenants.runAs(CLUB_2, () ->
                club2.stream().filter(s -> !isCascadedTow(s, club2)).forEach(this::driveToState));
    }

    private static boolean isCascadedTow(FlightSpec spec, List<FlightSpec> all) {
        if (spec.type() != FlightAircraftType.TOW) {
            return false;
        }
        return all.stream().anyMatch(g -> spec.id().equals(g.towFlightId()));
    }

    private static FlightSpec glider(UUID id, UUID clubId, UUID aircraftId, UUID pic,
                                     UUID startType, UUID home, int daysAgo,
                                     FlightProcessState target, @Nullable UUID towFlightId) {
        return new FlightSpec(id, clubId, FlightAircraftType.GLIDER, aircraftId, pic,
                startType, home, daysAgo, target, towFlightId);
    }

    private static FlightSpec tow(UUID id, UUID clubId, UUID aircraftId, UUID pic,
                                  UUID home, int daysAgo, FlightProcessState target) {
        return new FlightSpec(id, clubId, FlightAircraftType.TOW, aircraftId, pic,
                START_TYPE_AEROTOW, home, daysAgo, target, null);
    }

    private static FlightSpec motor(UUID id, UUID clubId, UUID aircraftId, UUID pic,
                                    UUID home, int daysAgo, FlightProcessState target) {
        return new FlightSpec(id, clubId, FlightAircraftType.MOTOR, aircraftId, pic,
                START_TYPE_MOTOR, home, daysAgo, target, null);
    }

    private void insertFlightRow(FlightSpec spec, LocalDate today) {
        LocalDate date = today.minusDays(spec.daysAgo());
        Instant start = date.atTime(10, 0).toInstant(ZoneOffset.UTC);
        Instant ldg = date.atTime(11, 0).toInstant(ZoneOffset.UTC);
        FlightOperationalData ops = new FlightOperationalData(
                date, start, ldg, start, ldg,
                spec.homeLocationId(), spec.homeLocationId(),
                null, null, null, null,
                null, spec.startTypeId(),
                (short) 1, (short) 1,
                false, false, null, null,
                "Showcase " + spec.type() + " flight", null, null, null,
                null, null, false);
        Flight f = switch (spec.type()) {
            case GLIDER -> Flight.createGlider(spec.aircraftId(),
                    FlightProcessState.NOT_PROCESSED.id(), ops);
            case TOW -> Flight.createTow(spec.aircraftId(),
                    FlightProcessState.NOT_PROCESSED.id(), ops);
            case MOTOR -> Flight.createMotor(spec.aircraftId(),
                    FlightProcessState.NOT_PROCESSED.id(), ops);
        };
        jdbc.update("""
                INSERT INTO t_flight (id, operating_club_id, aircraft_id, flight_date,
                        start_date_time, ldg_date_time, block_start_date_time, block_end_date_time,
                        start_location_id, ldg_location_id, start_type_id,
                        nr_of_ldgs, nr_of_ldgs_on_start_location,
                        no_start_time_information, no_ldg_time_information,
                        is_solo_flight, process_state_id, flight_aircraft_type_id, comment)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?::uuid, ?::uuid, ?::uuid,
                        ?, ?, ?, ?, ?, ?::uuid, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                spec.id().toString(), spec.clubId().toString(), spec.aircraftId().toString(),
                java.sql.Date.valueOf(date),
                java.sql.Timestamp.from(start), java.sql.Timestamp.from(ldg),
                java.sql.Timestamp.from(start), java.sql.Timestamp.from(ldg),
                spec.homeLocationId().toString(), spec.homeLocationId().toString(),
                spec.startTypeId().toString(),
                f.getNrOfLdgs(), f.getNrOfLdgsOnStartLocation(),
                f.isNoStartTimeInformation(), f.isNoLdgTimeInformation(), f.isSoloFlight(),
                FlightProcessState.NOT_PROCESSED.id().toString(),
                (int) spec.type().legacyId(), f.getComment());
    }

    private void insertCrewRow(FlightSpec spec, LocalDate today) {
        LocalDate date = today.minusDays(spec.daysAgo());
        Instant start = date.atTime(10, 0).toInstant(ZoneOffset.UTC);
        Instant ldg = date.atTime(11, 0).toInstant(ZoneOffset.UTC);
        CrewMemberSpec crew = new CrewMemberSpec(
                spec.picPersonId(), FlightCrewTypeIds.PILOT_OR_STUDENT,
                start, ldg, null, null, (short) 1, (short) 1);
        jdbc.update("""
                INSERT INTO t_flight_crew (id, flight_id, person_id, flight_crew_type_id,
                        begin_flight_datetime, end_flight_datetime, nr_of_ldgs, nr_of_starts)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                crewRowId(spec.id()).toString(), spec.id().toString(),
                crew.personId().toString(), crew.flightCrewTypeId().toString(),
                java.sql.Timestamp.from(start), java.sql.Timestamp.from(ldg),
                crew.nrOfLdgs(), crew.nrOfStarts());
    }

    private void linkTowIfPaired(FlightSpec spec) {
        if (spec.towFlightId() == null) {
            return;
        }
        jdbc.update(
                "UPDATE t_flight SET tow_flight_id = ?::uuid WHERE id = ?::uuid AND tow_flight_id IS NULL",
                spec.towFlightId().toString(), spec.id().toString());
    }

    private void driveToState(FlightSpec spec) {
        FlightId id = FlightId.of(spec.id());
        if (currentStateIdOf(spec.id()).equals(spec.targetState().id())) {
            return;
        }
        boolean cascade = spec.towFlightId() != null;
        switch (spec.targetState()) {
            case NOT_PROCESSED -> { }
            case VALID -> transition(id, cascade, FlightProcessState.VALID, TransitionTrigger.VALIDATOR);
            case INVALID -> transition(id, cascade, FlightProcessState.INVALID, TransitionTrigger.VALIDATOR);
            case LOCKED -> {
                transition(id, cascade, FlightProcessState.VALID, TransitionTrigger.VALIDATOR);
                transition(id, cascade, FlightProcessState.LOCKED, TransitionTrigger.LOCK_JOB);
            }
            case DELIVERY_BOOKED -> {
                transition(id, cascade, FlightProcessState.VALID, TransitionTrigger.VALIDATOR);
                transition(id, cascade, FlightProcessState.LOCKED, TransitionTrigger.LOCK_JOB);
                backdateLockedAt(spec);
                transition(id, cascade, FlightProcessState.DELIVERY_PREPARED, TransitionTrigger.DELIVERY_PREP);
                transition(id, cascade, FlightProcessState.DELIVERY_BOOKED, TransitionTrigger.BOOKING);
            }
            default -> throw new IllegalStateException(
                    "showcase-seed does not target " + spec.targetState());
        }
    }

    private UUID currentStateIdOf(UUID flightId) {
        String raw = jdbc.queryForObject(
                "SELECT process_state_id::text FROM t_flight WHERE id = ?::uuid",
                String.class, flightId.toString());
        return UUID.fromString(reqString(raw));
    }

    private static String reqString(@Nullable String value) {
        if (value == null) {
            throw new IllegalStateException("expected a non-null process_state_id for a seeded flight");
        }
        return value;
    }

    private void transition(FlightId id, boolean cascade,
                            FlightProcessState target, TransitionTrigger trigger) {
        if (cascade) {
            flightTransitions.transitionWithTowCascade(id, target, trigger);
        } else {
            flightTransitions.transition(id, target, trigger);
        }
    }

    private void backdateLockedAt(FlightSpec spec) {
        Instant lockedDay = LocalDate.now(ZoneOffset.UTC).minusDays(5)
                .atStartOfDay().toInstant(ZoneOffset.UTC);
        jdbc.update(
                "UPDATE t_flight SET locked_at = ? WHERE id = ?::uuid AND locked_at IS NOT NULL",
                java.sql.Timestamp.from(lockedDay), spec.id().toString());
        if (spec.towFlightId() != null) {
            jdbc.update(
                    "UPDATE t_flight SET locked_at = ? WHERE id = ?::uuid AND locked_at IS NOT NULL",
                    java.sql.Timestamp.from(lockedDay), spec.towFlightId().toString());
        }
    }

    private static UUID flightId(int n) {
        return UUID.fromString(String.format(
                "019e30c3-2c00-7801-8000-0000000008%02x", n));
    }

    private static UUID crewRowId(UUID flightId) {
        return UUID.fromString(flightId.toString().replaceFirst("-7801-", "-7901-"));
    }

    private static String reqUuid(@Nullable UUID value) {
        if (value == null) {
            throw new IllegalStateException("aggregate yielded a null required UUID");
        }
        return value.toString();
    }

    private static Instant reqInstant(@Nullable Instant value) {
        if (value == null) {
            throw new IllegalStateException("aggregate yielded a null required instant");
        }
        return value.truncatedTo(ChronoUnit.SECONDS);
    }
}
