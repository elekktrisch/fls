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

    private int countLocations(UUID clubId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_location WHERE club_id = ?::uuid AND deleted_on IS NULL",
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
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM t_aircraft WHERE managing_club_id = ?::uuid AND deleted_on IS NULL",
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
