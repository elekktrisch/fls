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

class TwoClubFixtureProductionPathIT extends PostgresIntegrationTest {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9-]{3,64}$");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;

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
