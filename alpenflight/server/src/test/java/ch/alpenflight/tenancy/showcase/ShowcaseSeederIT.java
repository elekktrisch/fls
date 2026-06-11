package ch.alpenflight.tenancy.showcase;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the on-demand showcase seed (J-3 T-02) loads its tenancy + principal
 * layer and is <strong>idempotent</strong> — the property the e2e display run
 * leans on (re-running the loader, or running it after the always-on dev seeds,
 * must not duplicate rows or change counts).
 *
 * <p>The {@link ShowcaseSeedRunner} is {@code @Profile("showcase")} so it does
 * NOT fire under the {@code test} profile (ADR 0021 — ITs stay lean); this IT
 * drives {@link ShowcaseSeeder#seed()} directly, twice, and asserts the second
 * run is a clean no-op.
 */
class ShowcaseSeederIT extends PostgresIntegrationTest {

    private static final UUID CLUB_1 = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final UUID CLUB_2 = UUID.fromString("019e30c3-2c00-7001-8000-000000000002");
    private static final String[] SHOWCASE_USERNAMES = {"pilot-empty1", "clubadmin-c2", "pilot-c2"};

    // Deterministic showcase masterdata keys (T-03a) — owned + pre-cleaned by
    // this IT under the ADR 0021 isolation rule. Aircraft key on the global
    // immatriculation; locations key on the per-club ICAO (pre-cleaned inline).
    private static final String[] SHOWCASE_IMMATS =
            {"HB-3001", "HB-TOW1", "HB-MOT1", "HB-3002", "HB-CHTR"};

    // Deterministic showcase flight ids (T-03b): id band 019e30c3-…-7801-…08NN.
    // The PIC persons + the pilot-empty1 user that must have ZERO crew rows.
    private static final UUID PERSON_PILOT1 = UUID.fromString("019e30c3-2c00-7601-8000-000000000601");
    private static final UUID PERSON_PILOT_C2 = UUID.fromString("019e30c3-2c00-7601-8000-000000000602");
    private static final UUID USER_PILOT_EMPTY1 = UUID.fromString("019e30c3-2c00-7100-8000-000000000020");
    private static final UUID FLIGHT_C1_AEROTOW_GLIDER_TODAY =
            UUID.fromString("019e30c3-2c00-7801-8000-000000000801");
    private static final UUID FLIGHT_C1_TOW_TODAY =
            UUID.fromString("019e30c3-2c00-7801-8000-000000000802");

    // FlightProcessState canonical ids (V3 seed / FlightProcessState enum).
    private static final String PS_NOT_PROCESSED = "019e2e15-2c00-7a98-8000-000000003a98";
    private static final String PS_INVALID = "019e2e15-2c00-7a99-8000-000000003a99";
    private static final String PS_VALID = "019e2e15-2c00-7a9a-8000-000000003a9a";
    private static final String PS_LOCKED = "019e2e15-2c00-7a9b-8000-000000003a9b";
    private static final String PS_DELIVERY_BOOKED = "019e2e15-2c00-7a9e-8000-000000003a9e";

    @Autowired
    private ShowcaseSeeder seeder;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void preClean() {
        // ADR 0021 isolation: own the deterministic showcase rows by their
        // stable keys and pre-clean so a re-run from a warm container is clean.
        // Aircraft state-history is FK-on-delete-cascade off t_aircraft, so
        // deleting the aircraft clears its airworthiness rows.
        // Flights first (FK from t_flight_crew → t_flight ON DELETE CASCADE
        // clears the crew rows; the self-FK tow_flight_id is ON DELETE SET NULL).
        // Then unlink + delete the PIC persons. Aircraft/location deletes follow
        // (a flight FK-references them, so flights must go first).
        jdbc.update("DELETE FROM t_flight WHERE id::text LIKE '019e30c3-2c00-7801-%'");
        jdbc.update("UPDATE t_user SET person_id = NULL WHERE person_id IN (?::uuid, ?::uuid)",
                PERSON_PILOT1.toString(), PERSON_PILOT_C2.toString());
        jdbc.update("DELETE FROM t_person WHERE id IN (?::uuid, ?::uuid)",
                PERSON_PILOT1.toString(), PERSON_PILOT_C2.toString());
        jdbc.update("DELETE FROM t_aircraft WHERE immatriculation IN ('HB-3001','HB-TOW1','HB-MOT1','HB-3002','HB-CHTR')");
        jdbc.update("DELETE FROM t_location WHERE icao_code IN ('LSZX','LSGB','LSPD','LSZW','LSGT','LSPM')");
        for (String u : SHOWCASE_USERNAMES) {
            jdbc.update("DELETE FROM t_user WHERE username = ?", u);
        }
        jdbc.update("DELETE FROM t_member_state WHERE club_id = ?::uuid", CLUB_2.toString());
        jdbc.update("DELETE FROM t_flight_type WHERE operating_club_id = ?::uuid", CLUB_2.toString());
        jdbc.update("DELETE FROM t_club WHERE id = ?::uuid", CLUB_2.toString());
    }

    @Test
    void seedsClubsPrincipalsAndReferenceData() {
        seeder.seed();

        // 2nd showcase club exists with deterministic id + slug.
        assertThat(jdbc.queryForObject(
                "SELECT slug FROM t_club WHERE id = ?::uuid", String.class, CLUB_2.toString()))
                .isEqualTo("showcase-club-2");

        // Reference data provisioned for the new club, same defaults a real club gets.
        assertThat(countMemberStates(CLUB_2)).isEqualTo(3);   // active / passive / junior
        assertThat(countFlightTypes(CLUB_2)).isEqualTo(4);    // training / glider-tow / private / ferry

        // The three net-new principals materialised with their tenants.
        assertThat(clubOf("pilot-empty1")).isEqualTo("019e30c3-2c00-7001-8000-000000000001"); // club-1, no flights
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

        // Second run must be a clean no-op (ON CONFLICT DO NOTHING everywhere).
        seeder.seed();

        assertThat(countShowcaseClubs()).isEqualTo(clubsAfterFirst).isEqualTo(1);
        assertThat(countShowcaseUsers()).isEqualTo(usersAfterFirst).isEqualTo(SHOWCASE_USERNAMES.length);
        assertThat(countMemberStates(CLUB_2)).isEqualTo(memberStatesAfterFirst).isEqualTo(3);
        assertThat(countFlightTypes(CLUB_2)).isEqualTo(flightTypesAfterFirst).isEqualTo(4);
    }

    @Test
    void seedsLocationsPerClub() {
        seeder.seed();

        // Each club gets a home airfield + two destination airfields (3 each),
        // tenant-scoped: the same ICAO catalog is private per club.
        assertThat(countLocations(CLUB_1)).isEqualTo(3);
        assertThat(countLocations(CLUB_2)).isEqualTo(3);

        // Home airfields carry the deterministic ICAO the seeder constants pin.
        assertThat(icaoExists(CLUB_1, "LSZX")).isTrue();
        assertThat(icaoExists(CLUB_2, "LSZW")).isTrue();
    }

    @Test
    void seedsAircraftFleetVariants() {
        seeder.seed();

        // Fleet variants by aircraft_type code (joined). Club 1 manages a
        // glider + a tow plane + a TMG; club 2 manages a glider + a charter
        // aircraft that club 1 reads cross-tenant (S-058).
        assertThat(typeCodeOf("HB-3001")).isEqualTo("GLIDER");
        assertThat(typeCodeOf("HB-TOW1")).isEqualTo("MOTOR_AIRCRAFT");
        assertThat(typeCodeOf("HB-MOT1")).isEqualTo("MOTOR_GLIDER");
        assertThat(typeCodeOf("HB-3002")).isEqualTo("GLIDER");
        assertThat(typeCodeOf("HB-CHTR")).isEqualTo("MOTOR_AIRCRAFT");

        // Manager-club scoping: 3 managed by club 1, 2 by club 2.
        assertThat(countAircraftManagedBy(CLUB_1)).isEqualTo(3);
        assertThat(countAircraftManagedBy(CLUB_2)).isEqualTo(2);

        // The towing types carry the towing-aircraft flag the picker filters on.
        assertThat(isTowingAircraft("HB-TOW1")).isTrue();

        // Every aircraft was registered through the domain with an open
        // airworthiness state period (changeState → OK), so the flyability
        // join the J-1 list reads is populated.
        for (String immat : SHOWCASE_IMMATS) {
            assertThat(openStateCount(immat))
                    .as("open airworthiness period for %s", immat)
                    .isEqualTo(1);
        }
    }

    @Test
    void locationsAndAircraftAreIdempotentAcrossReRuns() {
        seeder.seed();
        int locC1 = countLocations(CLUB_1);
        int locC2 = countLocations(CLUB_2);
        long aircraft = countShowcaseAircraft();
        long stateRows = countShowcaseStateRows();

        seeder.seed();

        assertThat(countLocations(CLUB_1)).isEqualTo(locC1).isEqualTo(3);
        assertThat(countLocations(CLUB_2)).isEqualTo(locC2).isEqualTo(3);
        assertThat(countShowcaseAircraft()).isEqualTo(aircraft).isEqualTo(SHOWCASE_IMMATS.length);
        // No duplicate airworthiness periods on re-run.
        assertThat(countShowcaseStateRows()).isEqualTo(stateRows).isEqualTo(SHOWCASE_IMMATS.length);
    }

    @Test
    void seedsFlightMatrixPerClubAndState() {
        seeder.seed();

        // Documented per-club totals (showcase/README.md): 8 in club-1, 6 in club-2.
        assertThat(countFlights(CLUB_1)).isEqualTo(8);
        assertThat(countFlights(CLUB_2)).isEqualTo(6);

        // Club-1 per-state (NotProcessed 3 / Valid 1 / Invalid 1 / Locked 2 / Booked 1).
        assertThat(countFlightsInState(CLUB_1, PS_NOT_PROCESSED)).isEqualTo(3);
        assertThat(countFlightsInState(CLUB_1, PS_VALID)).isEqualTo(1);
        assertThat(countFlightsInState(CLUB_1, PS_INVALID)).isEqualTo(1);
        assertThat(countFlightsInState(CLUB_1, PS_LOCKED)).isEqualTo(2);
        assertThat(countFlightsInState(CLUB_1, PS_DELIVERY_BOOKED)).isEqualTo(1);

        // Club-2 per-state (NotProcessed 1 / Valid 1 / Invalid 1 / Locked 2 / Booked 1).
        assertThat(countFlightsInState(CLUB_2, PS_NOT_PROCESSED)).isEqualTo(1);
        assertThat(countFlightsInState(CLUB_2, PS_VALID)).isEqualTo(1);
        assertThat(countFlightsInState(CLUB_2, PS_INVALID)).isEqualTo(1);
        assertThat(countFlightsInState(CLUB_2, PS_LOCKED)).isEqualTo(2);
        assertThat(countFlightsInState(CLUB_2, PS_DELIVERY_BOOKED)).isEqualTo(1);
    }

    @Test
    void seedsTheDocumentedAdminDashboardCounts() {
        seeder.seed();

        // Club-admin "today's flights" tile (T-08/T-09 assert these exact numbers).
        assertThat(countTodaysFlights(CLUB_1)).isEqualTo(3);
        assertThat(countTodaysFlights(CLUB_2)).isEqualTo(1);

        // Club-admin "pending validation" tile = NotProcessed + Invalid.
        assertThat(countPendingValidation(CLUB_1)).isEqualTo(4); // 3 NotProcessed + 1 Invalid
        assertThat(countPendingValidation(CLUB_2)).isEqualTo(2); // 1 NotProcessed + 1 Invalid
    }

    @Test
    void locksWereReachedThroughTheTimeGateWithLockedAtStamped() {
        seeder.seed();
        // Every Locked / DeliveryBooked flight carries a non-null locked_at —
        // proof the Valid → Locked edge ran through the domain (it stamps it).
        Integer lockedWithoutTimestamp = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight WHERE id::text LIKE '019e30c3-2c00-7801-%' "
                        + "AND process_state_id IN (?::uuid, ?::uuid) AND locked_at IS NULL",
                Integer.class, PS_LOCKED, PS_DELIVERY_BOOKED);
        assertThat(lockedWithoutTimestamp).isZero();
    }

    @Test
    void pairsTheAerotowGliderToItsTowRow() {
        seeder.seed();
        // The today aerotow glider links the today tow row (S-063 glider↔tow).
        String towLink = jdbc.queryForObject(
                "SELECT tow_flight_id::text FROM t_flight WHERE id = ?::uuid",
                String.class, FLIGHT_C1_AEROTOW_GLIDER_TODAY.toString());
        assertThat(towLink).isEqualTo(FLIGHT_C1_TOW_TODAY.toString());
        // The tow target is itself a TOW flight (aircraft_type 2).
        Integer towType = jdbc.queryForObject(
                "SELECT flight_aircraft_type_id FROM t_flight WHERE id = ?::uuid",
                Integer.class, FLIGHT_C1_TOW_TODAY.toString());
        assertThat(towType).isEqualTo(2);
    }

    @Test
    void backfillsFlightReportRowsForEverySeededFlight() {
        seeder.seed();
        // J-7 RM-2 phase C: the JDBC base inserts bypass the save-time
        // projector, so the seeder ends with a per-club read-model rebuild —
        // every live showcase flight (both clubs) must own a report row.
        Long reportRows = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight_report_row "
                        + "WHERE flight_id::text LIKE '019e30c3-2c00-7801-%'",
                Long.class);
        assertThat(reportRows).isEqualTo(countShowcaseFlights()).isEqualTo(14);
        // The aerotow glider's row carries the reconstructed tow block +
        // decorations — proof the rebuild ran the real projector, not a stub.
        var gliderRow = jdbc.queryForMap(
                "SELECT pilot_name, tow_flight_id::text AS tow_flight_id, tow_immatriculation "
                        + "FROM t_flight_report_row WHERE flight_id = ?::uuid",
                FLIGHT_C1_AEROTOW_GLIDER_TODAY.toString());
        assertThat(gliderRow.get("tow_flight_id")).isEqualTo(FLIGHT_C1_TOW_TODAY.toString());
        assertThat(gliderRow.get("tow_immatriculation")).isEqualTo("HB-TOW1");
        assertThat(gliderRow.get("pilot_name")).asString().isNotBlank();
    }

    @Test
    void pilot1IsPicOnSeededFlightsButPilotEmpty1HasZeroCrew() {
        seeder.seed();
        // pilot1 (club-1) + pilot-c2 (club-2) are PIC on every flight they fly.
        assertThat(countCrewForPerson(PERSON_PILOT1)).isEqualTo(8);
        assertThat(countCrewForPerson(PERSON_PILOT_C2)).isEqualTo(6);
        // The empty-state principal owns no crew row at all.
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
        // States stay put — a re-run drives no further transitions.
        assertThat(countFlightsInState(CLUB_1, PS_DELIVERY_BOOKED)).isEqualTo(1);
        assertThat(countFlightsInState(CLUB_2, PS_DELIVERY_BOOKED)).isEqualTo(1);
    }

    private int countFlights(UUID clubId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight WHERE operating_club_id = ?::uuid "
                        + "AND id::text LIKE '019e30c3-2c00-7801-%' AND deleted_on IS NULL",
                Integer.class, clubId.toString());
        return n == null ? 0 : n;
    }

    private int countFlightsInState(UUID clubId, String processStateId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight WHERE operating_club_id = ?::uuid "
                        + "AND process_state_id = ?::uuid AND id::text LIKE '019e30c3-2c00-7801-%' "
                        + "AND deleted_on IS NULL",
                Integer.class, clubId.toString(), processStateId);
        return n == null ? 0 : n;
    }

    private int countTodaysFlights(UUID clubId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight WHERE operating_club_id = ?::uuid "
                        + "AND flight_date = CURRENT_DATE AND id::text LIKE '019e30c3-2c00-7801-%' "
                        + "AND deleted_on IS NULL",
                Integer.class, clubId.toString());
        return n == null ? 0 : n;
    }

    private int countPendingValidation(UUID clubId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight WHERE operating_club_id = ?::uuid "
                        + "AND process_state_id IN (?::uuid, ?::uuid) "
                        + "AND id::text LIKE '019e30c3-2c00-7801-%' AND deleted_on IS NULL",
                Integer.class, clubId.toString(), PS_NOT_PROCESSED, PS_INVALID);
        return n == null ? 0 : n;
    }

    private int countCrewForPerson(UUID personId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight_crew c JOIN t_flight f ON f.id = c.flight_id "
                        + "WHERE c.person_id = ?::uuid AND c.deleted_on IS NULL "
                        + "AND f.id::text LIKE '019e30c3-2c00-7801-%'",
                Integer.class, personId.toString());
        return n == null ? 0 : n;
    }

    private int crewCountForUser(UUID userId) {
        // Resolve the user's person (may be null), then any crew rows for it.
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight_crew c "
                        + "JOIN t_user u ON u.person_id = c.person_id "
                        + "WHERE u.id = ?::uuid AND c.deleted_on IS NULL",
                Integer.class, userId.toString());
        return n == null ? 0 : n;
    }

    private long countShowcaseFlights() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight WHERE id::text LIKE '019e30c3-2c00-7801-%' "
                        + "AND deleted_on IS NULL",
                Long.class);
        return n == null ? 0 : n;
    }

    private long countShowcaseCrew() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight_crew c JOIN t_flight f ON f.id = c.flight_id "
                        + "WHERE f.id::text LIKE '019e30c3-2c00-7801-%' AND c.deleted_on IS NULL",
                Long.class);
        return n == null ? 0 : n;
    }

    private int countLocations(UUID clubId) {
        // Count only the SHOWCASE-owned locations for the club (deterministic id
        // band 019e30c3-…-7301-…). ADR 0021: CLUB_1 is the shared dev club other
        // ITs also populate, so a bare club_id count over the shared container is
        // non-deterministic; keying on the seeder's own id band still catches a
        // missing/duplicated showcase row while ignoring foreign rows.
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_location WHERE club_id = ?::uuid "
                        + "AND id::text LIKE '019e30c3-2c00-7301-%' AND deleted_on IS NULL",
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
                        + "('HB-3001','HB-TOW1','HB-MOT1','HB-3002','HB-CHTR') AND deleted_on IS NULL",
                Long.class);
        return n == null ? 0 : n;
    }

    private long countShowcaseStateRows() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM t_aircraft_aircraft_state s "
                        + "JOIN t_aircraft a ON a.id = s.aircraft_id "
                        + "WHERE a.immatriculation IN ('HB-3001','HB-TOW1','HB-MOT1','HB-3002','HB-CHTR')",
                Long.class);
        return n == null ? 0 : n;
    }

    private long countAircraftManagedBy(UUID clubId) {
        // Count only the SHOWCASE-owned fleet for the club (the seeder's fixed
        // immats). CLUB_1 is the shared dev club other ITs also populate, so a
        // bare managing_club_id count over the shared container is
        // non-deterministic; keying on the seeded immats still catches a
        // missing/misassigned showcase aircraft while ignoring foreign rows.
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM t_aircraft WHERE managing_club_id = ?::uuid "
                        + "AND immatriculation IN ('HB-3001','HB-TOW1','HB-MOT1','HB-3002','HB-CHTR') "
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
