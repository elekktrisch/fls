package ch.alpenflight.tenancy.showcase;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ShowcaseSeederIT extends PostgresIntegrationTest {

    private static final UUID CLUB_1 = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final UUID CLUB_2 = UUID.fromString("019e30c3-2c00-7001-8000-000000000002");
    private static final String[] SHOWCASE_USERNAMES = {"pilot-empty1", "clubadmin-c2", "pilot-c2"};

    private static final String[] SHOWCASE_IMMATS =
            {"HB-3001", "HB-TOW1", "HB-MOT1", "HB-3002", "HB-CHTR"};

    private static final UUID PERSON_PILOT1 = UUID.fromString("019e30c3-2c00-7601-8000-000000000601");
    private static final UUID PERSON_PILOT_C2 = UUID.fromString("019e30c3-2c00-7601-8000-000000000602");
    private static final UUID USER_PILOT_EMPTY1 = UUID.fromString("019e30c3-2c00-7100-8000-000000000020");
    private static final UUID FLIGHT_C1_AEROTOW_GLIDER_TODAY =
            UUID.fromString("019e30c3-2c00-7801-8000-000000000801");
    private static final UUID FLIGHT_C1_TOW_TODAY =
            UUID.fromString("019e30c3-2c00-7801-8000-000000000802");

    private static final String PS_NOT_PROCESSED = "019e2e15-2c00-7a98-8000-000000003a98";
    private static final String PS_INVALID = "019e2e15-2c00-7a99-8000-000000003a99";
    private static final String PS_VALID = "019e2e15-2c00-7a9a-8000-000000003a9a";
    private static final String PS_LOCKED = "019e2e15-2c00-7a9b-8000-000000003a9b";
    private static final String PS_DELIVERY_BOOKED = "019e2e15-2c00-7a9e-8000-000000003a9e";

    private static final String SHOWCASE_OWNED_FLIGHT_IDS_SQL = "'019e30c3-2c00-7801-%'";
    private static final String SHOWCASE_OWNED_LOCATION_IDS_SQL = "'019e30c3-2c00-7301-%'";
    private static final String SHOWCASE_OWNED_IMMATS_SQL = Arrays.stream(SHOWCASE_IMMATS)
            .map(immat -> "'" + immat + "'")
            .collect(Collectors.joining(",", "(", ")"));

    private static final int MEMBER_STATES_SEEDED_PER_CLUB = 3;
    private static final int FLIGHT_TYPES_SEEDED_PER_CLUB = 4;
    private static final int FLIGHT_AIRCRAFT_TYPE_TOW = 2;

    @Autowired
    private ShowcaseSeeder seeder;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void preCleanTheRowsThisItOwns() {
        deleteShowcaseFlightsFirstSoCrewCascadesAndTowLinksNullOut();
        unlinkThenDeleteThePicPersons();
        deleteShowcaseAircraftCascadingTheirAirworthinessRows();
        deleteShowcaseLocationsNowThatNoFlightReferencesThem();
        deleteShowcaseUsers();
        deleteShowcaseClub2WithItsPerClubReferenceData();
    }

    private void deleteShowcaseFlightsFirstSoCrewCascadesAndTowLinksNullOut() {
        jdbc.update("DELETE FROM t_flight WHERE id::text LIKE " + SHOWCASE_OWNED_FLIGHT_IDS_SQL);
    }

    private void unlinkThenDeleteThePicPersons() {
        jdbc.update("UPDATE t_user SET person_id = NULL WHERE person_id IN (?::uuid, ?::uuid)",
                PERSON_PILOT1.toString(), PERSON_PILOT_C2.toString());
        jdbc.update("DELETE FROM t_person WHERE id IN (?::uuid, ?::uuid)",
                PERSON_PILOT1.toString(), PERSON_PILOT_C2.toString());
    }

    private void deleteShowcaseAircraftCascadingTheirAirworthinessRows() {
        jdbc.update("DELETE FROM t_aircraft WHERE immatriculation IN " + SHOWCASE_OWNED_IMMATS_SQL);
    }

    private void deleteShowcaseLocationsNowThatNoFlightReferencesThem() {
        jdbc.update("DELETE FROM t_location WHERE icao_code IN ('LSZX','LSGB','LSPD','LSZW','LSGT','LSPM')");
    }

    private void deleteShowcaseUsers() {
        for (String u : SHOWCASE_USERNAMES) {
            jdbc.update("DELETE FROM t_user WHERE username = ?", u);
        }
    }

    private void deleteShowcaseClub2WithItsPerClubReferenceData() {
        jdbc.update("DELETE FROM t_member_state WHERE club_id = ?::uuid", CLUB_2.toString());
        jdbc.update("DELETE FROM t_flight_type WHERE operating_club_id = ?::uuid", CLUB_2.toString());
        jdbc.update("DELETE FROM t_club WHERE id = ?::uuid", CLUB_2.toString());
    }

    @Test
    void seedsClubsPrincipalsAndReferenceData() {
        seeder.seed();

        assertThat(jdbc.queryForObject(
                "SELECT slug FROM t_club WHERE id = ?::uuid", String.class, CLUB_2.toString()))
                .isEqualTo("showcase-club-2");

        assertThat(countMemberStates(CLUB_2)).isEqualTo(MEMBER_STATES_SEEDED_PER_CLUB);
        assertThat(countFlightTypes(CLUB_2)).isEqualTo(FLIGHT_TYPES_SEEDED_PER_CLUB);

        assertThat(clubOf("pilot-empty1")).isEqualTo("019e30c3-2c00-7001-8000-000000000001");
        assertThat(clubOf("clubadmin-c2")).isEqualTo(CLUB_2.toString());
        assertThat(clubOf("pilot-c2")).isEqualTo(CLUB_2.toString());
    }

    @Test
    void isIdempotentAcrossReRuns() {
        seeder.seed();
        long clubsAfterFirst = countShowcaseClubs();
        long usersAfterFirst = countShowcaseUsers();
        int memberStatesAfterFirst = countMemberStates(CLUB_2);
        int flightTypesAfterFirst = countFlightTypes(CLUB_2);

        seeder.seed();

        assertThat(countShowcaseClubs()).isEqualTo(clubsAfterFirst).isEqualTo(1);
        assertThat(countShowcaseUsers()).isEqualTo(usersAfterFirst).isEqualTo(SHOWCASE_USERNAMES.length);
        assertThat(countMemberStates(CLUB_2)).isEqualTo(memberStatesAfterFirst)
                .isEqualTo(MEMBER_STATES_SEEDED_PER_CLUB);
        assertThat(countFlightTypes(CLUB_2)).isEqualTo(flightTypesAfterFirst)
                .isEqualTo(FLIGHT_TYPES_SEEDED_PER_CLUB);
    }

    @Test
    void seedsLocationsPerClub() {
        seeder.seed();

        assertThat(countShowcaseOwnedLocations(CLUB_1)).isEqualTo(3);
        assertThat(countShowcaseOwnedLocations(CLUB_2)).isEqualTo(3);

        assertThat(icaoExists(CLUB_1, "LSZX")).isTrue();
        assertThat(icaoExists(CLUB_2, "LSZW")).isTrue();
    }

    @Test
    void seedsAircraftFleetVariants() {
        seeder.seed();

        assertThat(typeCodeOf("HB-3001")).isEqualTo("GLIDER");
        assertThat(typeCodeOf("HB-TOW1")).isEqualTo("MOTOR_AIRCRAFT");
        assertThat(typeCodeOf("HB-MOT1")).isEqualTo("MOTOR_GLIDER");
        assertThat(typeCodeOf("HB-3002")).isEqualTo("GLIDER");
        assertThat(typeCodeOf("HB-CHTR")).isEqualTo("MOTOR_AIRCRAFT");

        assertThat(countShowcaseOwnedAircraftManagedBy(CLUB_1)).isEqualTo(3);
        assertThat(countShowcaseOwnedAircraftManagedBy(CLUB_2)).isEqualTo(2);

        assertThat(isTowingAircraft("HB-TOW1"))
                .as("the tow plane carries the flag the aircraft picker filters on")
                .isTrue();

        for (String immat : SHOWCASE_IMMATS) {
            assertThat(openStateCount(immat))
                    .as("open airworthiness period for %s", immat)
                    .isEqualTo(1);
        }
    }

    @Test
    void locationsAndAircraftAreIdempotentAcrossReRuns() {
        seeder.seed();
        int locC1 = countShowcaseOwnedLocations(CLUB_1);
        int locC2 = countShowcaseOwnedLocations(CLUB_2);
        long aircraft = countShowcaseAircraft();
        long stateRows = countShowcaseStateRows();

        seeder.seed();

        assertThat(countShowcaseOwnedLocations(CLUB_1)).isEqualTo(locC1).isEqualTo(3);
        assertThat(countShowcaseOwnedLocations(CLUB_2)).isEqualTo(locC2).isEqualTo(3);
        assertThat(countShowcaseAircraft()).isEqualTo(aircraft).isEqualTo(SHOWCASE_IMMATS.length);
        assertThat(countShowcaseStateRows()).isEqualTo(stateRows).isEqualTo(SHOWCASE_IMMATS.length);
    }

    @Test
    void seedsFlightMatrixPerClubAndState() {
        seeder.seed();

        assertThat(countFlights(CLUB_1)).isEqualTo(8);
        assertThat(countFlights(CLUB_2)).isEqualTo(6);

        assertThat(countFlightsInState(CLUB_1, PS_NOT_PROCESSED)).isEqualTo(3);
        assertThat(countFlightsInState(CLUB_1, PS_VALID)).isEqualTo(1);
        assertThat(countFlightsInState(CLUB_1, PS_INVALID)).isEqualTo(1);
        assertThat(countFlightsInState(CLUB_1, PS_LOCKED)).isEqualTo(2);
        assertThat(countFlightsInState(CLUB_1, PS_DELIVERY_BOOKED)).isEqualTo(1);

        assertThat(countFlightsInState(CLUB_2, PS_NOT_PROCESSED)).isEqualTo(1);
        assertThat(countFlightsInState(CLUB_2, PS_VALID)).isEqualTo(1);
        assertThat(countFlightsInState(CLUB_2, PS_INVALID)).isEqualTo(1);
        assertThat(countFlightsInState(CLUB_2, PS_LOCKED)).isEqualTo(2);
        assertThat(countFlightsInState(CLUB_2, PS_DELIVERY_BOOKED)).isEqualTo(1);
    }

    @Test
    void seedsTheDocumentedAdminDashboardCounts() {
        seeder.seed();

        assertThat(countTodaysFlights(CLUB_1)).isEqualTo(3);
        assertThat(countTodaysFlights(CLUB_2)).isEqualTo(1);

        assertThat(countPendingValidation(CLUB_1)).isEqualTo(4);
        assertThat(countPendingValidation(CLUB_2)).isEqualTo(2);
    }

    @Test
    void locksWereReachedThroughTheTimeGateWithLockedAtStamped() {
        seeder.seed();
        Integer lockedWithoutTimestamp = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight WHERE id::text LIKE " + SHOWCASE_OWNED_FLIGHT_IDS_SQL + " "
                        + "AND process_state_id IN (?::uuid, ?::uuid) AND locked_at IS NULL",
                Integer.class, PS_LOCKED, PS_DELIVERY_BOOKED);
        assertThat(lockedWithoutTimestamp).isZero();
    }

    @Test
    void pairsTheAerotowGliderToItsTowRow() {
        seeder.seed();
        String towLink = jdbc.queryForObject(
                "SELECT tow_flight_id::text FROM t_flight WHERE id = ?::uuid",
                String.class, FLIGHT_C1_AEROTOW_GLIDER_TODAY.toString());
        assertThat(towLink).isEqualTo(FLIGHT_C1_TOW_TODAY.toString());
        Integer towType = jdbc.queryForObject(
                "SELECT flight_aircraft_type_id FROM t_flight WHERE id = ?::uuid",
                Integer.class, FLIGHT_C1_TOW_TODAY.toString());
        assertThat(towType).isEqualTo(FLIGHT_AIRCRAFT_TYPE_TOW);
    }

    @Test
    void rebuildsFlightReportRowsForEverySeededFlight_becauseTheJdbcInsertsBypassTheProjector() {
        seeder.seed();
        Long reportRows = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight_report_row "
                        + "WHERE flight_id::text LIKE " + SHOWCASE_OWNED_FLIGHT_IDS_SQL,
                Long.class);
        assertThat(reportRows).isEqualTo(countShowcaseFlights()).isEqualTo(14);
        var gliderRow = jdbc.queryForMap(
                "SELECT pilot_name, tow_flight_id::text AS tow_flight_id, tow_immatriculation "
                        + "FROM t_flight_report_row WHERE flight_id = ?::uuid",
                FLIGHT_C1_AEROTOW_GLIDER_TODAY.toString());
        assertThat(gliderRow.get("tow_flight_id"))
                .as("the reconstructed tow block proves the real projector ran, not a stub")
                .isEqualTo(FLIGHT_C1_TOW_TODAY.toString());
        assertThat(gliderRow.get("tow_immatriculation")).isEqualTo("HB-TOW1");
        assertThat(gliderRow.get("pilot_name")).asString().isNotBlank();
    }

    @Test
    void pilot1IsPicOnSeededFlightsButPilotEmpty1HasZeroCrew() {
        seeder.seed();
        assertThat(countCrewForPerson(PERSON_PILOT1)).isEqualTo(8);
        assertThat(countCrewForPerson(PERSON_PILOT_C2)).isEqualTo(6);
        assertThat(crewCountForUser(USER_PILOT_EMPTY1)).isZero();
    }

    @Test
    void flightMatrixIsIdempotentAcrossReRuns() {
        seeder.seed();
        long flightsAfterFirst = countShowcaseFlights();
        long crewAfterFirst = countShowcaseCrew();

        seeder.seed();

        assertThat(countShowcaseFlights()).isEqualTo(flightsAfterFirst).isEqualTo(14);
        assertThat(countShowcaseCrew()).isEqualTo(crewAfterFirst).isEqualTo(14);
        assertThat(countFlightsInState(CLUB_1, PS_DELIVERY_BOOKED)).isEqualTo(1);
        assertThat(countFlightsInState(CLUB_2, PS_DELIVERY_BOOKED)).isEqualTo(1);
    }

    private int countFlights(UUID clubId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight WHERE operating_club_id = ?::uuid "
                        + "AND id::text LIKE " + SHOWCASE_OWNED_FLIGHT_IDS_SQL + " AND deleted_on IS NULL",
                Integer.class, clubId.toString());
        return n == null ? 0 : n;
    }

    private int countFlightsInState(UUID clubId, String processStateId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight WHERE operating_club_id = ?::uuid "
                        + "AND process_state_id = ?::uuid AND id::text LIKE " + SHOWCASE_OWNED_FLIGHT_IDS_SQL + " "
                        + "AND deleted_on IS NULL",
                Integer.class, clubId.toString(), processStateId);
        return n == null ? 0 : n;
    }

    private int countTodaysFlights(UUID clubId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight WHERE operating_club_id = ?::uuid "
                        + "AND flight_date = CURRENT_DATE AND id::text LIKE " + SHOWCASE_OWNED_FLIGHT_IDS_SQL + " "
                        + "AND deleted_on IS NULL",
                Integer.class, clubId.toString());
        return n == null ? 0 : n;
    }

    private int countPendingValidation(UUID clubId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight WHERE operating_club_id = ?::uuid "
                        + "AND process_state_id IN (?::uuid, ?::uuid) "
                        + "AND id::text LIKE " + SHOWCASE_OWNED_FLIGHT_IDS_SQL + " AND deleted_on IS NULL",
                Integer.class, clubId.toString(), PS_NOT_PROCESSED, PS_INVALID);
        return n == null ? 0 : n;
    }

    private int countCrewForPerson(UUID personId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight_crew c JOIN t_flight f ON f.id = c.flight_id "
                        + "WHERE c.person_id = ?::uuid AND c.deleted_on IS NULL "
                        + "AND f.id::text LIKE " + SHOWCASE_OWNED_FLIGHT_IDS_SQL,
                Integer.class, personId.toString());
        return n == null ? 0 : n;
    }

    private int crewCountForUser(UUID userId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight_crew c "
                        + "JOIN t_user u ON u.person_id = c.person_id "
                        + "WHERE u.id = ?::uuid AND c.deleted_on IS NULL",
                Integer.class, userId.toString());
        return n == null ? 0 : n;
    }

    private long countShowcaseFlights() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight WHERE id::text LIKE " + SHOWCASE_OWNED_FLIGHT_IDS_SQL + " "
                        + "AND deleted_on IS NULL",
                Long.class);
        return n == null ? 0 : n;
    }

    private long countShowcaseCrew() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight_crew c JOIN t_flight f ON f.id = c.flight_id "
                        + "WHERE f.id::text LIKE " + SHOWCASE_OWNED_FLIGHT_IDS_SQL + " AND c.deleted_on IS NULL",
                Long.class);
        return n == null ? 0 : n;
    }

    private int countShowcaseOwnedLocations(UUID clubId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_location WHERE club_id = ?::uuid "
                        + "AND id::text LIKE " + SHOWCASE_OWNED_LOCATION_IDS_SQL + " AND deleted_on IS NULL",
                Integer.class, clubId.toString());
        return n == null ? 0 : n;
    }

    private boolean icaoExists(UUID clubId, String icao) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_location WHERE club_id = ?::uuid AND icao_code = ? AND deleted_on IS NULL",
                Integer.class, clubId.toString(), icao);
        return n != null && n > 0;
    }

    private long countShowcaseAircraft() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM t_aircraft WHERE immatriculation IN "
                        + SHOWCASE_OWNED_IMMATS_SQL + " AND deleted_on IS NULL",
                Long.class);
        return n == null ? 0 : n;
    }

    private long countShowcaseStateRows() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM t_aircraft_aircraft_state s "
                        + "JOIN t_aircraft a ON a.id = s.aircraft_id "
                        + "WHERE a.immatriculation IN " + SHOWCASE_OWNED_IMMATS_SQL,
                Long.class);
        return n == null ? 0 : n;
    }

    private long countShowcaseOwnedAircraftManagedBy(UUID clubId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM t_aircraft WHERE managing_club_id = ?::uuid "
                        + "AND immatriculation IN " + SHOWCASE_OWNED_IMMATS_SQL + " "
                        + "AND deleted_on IS NULL",
                Long.class, clubId.toString());
        return n == null ? 0 : n;
    }

    private String typeCodeOf(String immat) {
        return jdbc.queryForObject(
                "SELECT t.code FROM t_aircraft a JOIN t_aircraft_type t ON t.id = a.aircraft_type_id "
                        + "WHERE a.immatriculation = ?",
                String.class, immat);
    }

    private boolean isTowingAircraft(String immat) {
        Boolean b = jdbc.queryForObject(
                "SELECT is_towing_aircraft FROM t_aircraft WHERE immatriculation = ?",
                Boolean.class, immat);
        return Boolean.TRUE.equals(b);
    }

    private int openStateCount(String immat) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_aircraft_aircraft_state s "
                        + "JOIN t_aircraft a ON a.id = s.aircraft_id "
                        + "WHERE a.immatriculation = ? AND s.valid_to IS NULL",
                Integer.class, immat);
        return n == null ? 0 : n;
    }

    private long countShowcaseClubs() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM t_club WHERE id = ?::uuid", Long.class, CLUB_2.toString());
        return n == null ? 0 : n;
    }

    private long countShowcaseUsers() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM t_user WHERE username IN ('pilot-empty1','clubadmin-c2','pilot-c2')",
                Long.class);
        return n == null ? 0 : n;
    }

    private int countMemberStates(UUID clubId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_member_state WHERE club_id = ?::uuid AND deleted_on IS NULL",
                Integer.class, clubId.toString());
        return n == null ? 0 : n;
    }

    private int countFlightTypes(UUID clubId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight_type WHERE operating_club_id = ?::uuid AND deleted_on IS NULL",
                Integer.class, clubId.toString());
        return n == null ? 0 : n;
    }

    private String clubOf(String username) {
        return jdbc.queryForObject(
                "SELECT club_id::text FROM t_user WHERE username = ?", String.class, username);
    }
}
