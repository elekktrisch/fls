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

    private static final UUID CLUB_2 = UUID.fromString("019e30c3-2c00-7001-8000-000000000002");
    private static final String[] SHOWCASE_USERNAMES = {"pilot-empty1", "clubadmin-c2", "pilot-c2"};

    @Autowired
    private ShowcaseSeeder seeder;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void preClean() {
        // ADR 0021 isolation: own the deterministic showcase rows by their
        // stable keys and pre-clean so a re-run from a warm container is clean.
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
