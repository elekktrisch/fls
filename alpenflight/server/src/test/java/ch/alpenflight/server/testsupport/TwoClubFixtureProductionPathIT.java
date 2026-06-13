package ch.alpenflight.server.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the J-26 T-19a production-create path of {@link TwoClubFixture}: the
 * fixture mints two clubs through the real {@code Club.create} →
 * {@code ClubRepository.save} path (no seeding JDBC), the ids are distinct +
 * minted by JPA, the slugs are valid under {@code Club}'s
 * {@code ^[a-z0-9-]{3,64}$} rule, and the rows land under the operator
 * Deployment — exactly as {@code ClubsService.createClub} produces them.
 *
 * <p>The 28 existing consumers stay on the deprecated pinned-id path and are
 * proved by the whole-suite {@code ./gradlew check}; this IT covers the new
 * path in isolation.
 */
class TwoClubFixtureProductionPathIT extends PostgresIntegrationTest {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9-]{3,64}$");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;

    // Class-unique prefix slot (ux_club_key / ux_club_slug). "T19A" is owned by
    // no other TwoClubFixture consumer — IT_FRQ collides with
    // FlightReportQueryServiceIT, so this IT derives its own from the task id.
    private static final String NAME_PREFIX = "T19A_";
    private static final String KEY_PREFIX = "T19A";

    @Test
    void production_path_mints_two_distinct_clubs_with_valid_slugs() {
        TwoClubFixture fixture = new TwoClubFixture(
                jdbc, clubs, countries, clubStates, NAME_PREFIX, KEY_PREFIX);
        fixture.seed();

        UUID a = fixture.clubA();
        UUID b = fixture.clubB();

        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        assertThat(a).isNotEqualTo(b);

        // Both rows exist under the operator Deployment with a valid slug.
        assertThat(slugOf(a)).matches(SLUG_PATTERN);
        assertThat(slugOf(b)).matches(SLUG_PATTERN);
        assertThat(slugOf(a)).isNotEqualTo(slugOf(b));
        assertThat(deploymentOf(a)).isEqualTo(Deployment.OPERATOR_ID);
        assertThat(deploymentOf(b)).isEqualTo(Deployment.OPERATOR_ID);
    }

    @Test
    void production_path_is_rerunnable_in_the_same_jvm() {
        new TwoClubFixture(jdbc, clubs, countries, clubStates, NAME_PREFIX, KEY_PREFIX).seed();
        TwoClubFixture second =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, NAME_PREFIX, KEY_PREFIX);
        // A second seed at the same deterministic slugs must not trip
        // ux_club_slug — the production path pre-cleans by slug.
        second.seed();
        assertThat(second.clubA()).isNotEqualTo(second.clubB());
    }

    private String slugOf(UUID clubId) {
        return jdbc.queryForObject(
                "SELECT slug FROM t_club WHERE id = ?::uuid", String.class, clubId.toString());
    }

    private UUID deploymentOf(UUID clubId) {
        return jdbc.queryForObject(
                "SELECT deployment_id FROM t_club WHERE id = ?::uuid", UUID.class, clubId.toString());
    }
}
