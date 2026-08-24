package ch.alpenflight.clubs.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class ClubsDeploymentVisibilityIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NAME_PREFIX = "IT_CDV_";
    private static final String KEY_PREFIX = "IT_CV";

    @Autowired private TestRestTemplate rest;
    @Autowired private JwtTestFixture jwts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;

    private UUID realClubA;
    private UUID realClubB;
    private UUID sandboxSeatClub;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, NAME_PREFIX, KEY_PREFIX);
        fixture.seed();
        realClubA = fixture.clubA();
        realClubB = fixture.clubB();
        sandboxSeatClub =
                fixture.seedAdditionalClubInDeployment(Deployment.SANDBOX_ID, "sandboxseat");
        TenantTestContext.clear();
    }

    @Test
    void a_flight_operator_reads_only_the_clubs_of_its_own_deployment() {
        List<String> visible = clubIdsReadBy(jwts.mint(c -> c
                .claim("clubId", realClubA.toString())
                .claim("preferred_username", "flight-operator")
                .claim("realm_access", Map.of("roles", List.of("FLIGHT_OPERATOR")))));

        assertThat(visible)
                .as("a real club's flight operator must not read the sandbox seat clubs")
                .contains(external(realClubA), external(realClubB))
                .doesNotContain(external(sandboxSeatClub));
    }

    @Test
    void a_system_administrator_still_reads_the_sandbox_seat_clubs() {
        List<String> visible = clubIdsReadBy(jwts.mint(c -> c
                .claim("preferred_username", "sysadmin")
                .claim("realm_access", Map.of("roles", List.of("SYSTEM_ADMINISTRATOR")))));

        assertThat(visible)
                .as("the platform administrator operates the seat pool, so the seat clubs stay "
                        + "visible to it — a deliberate exclusion from the Deployment seal")
                .contains(external(sandboxSeatClub), external(realClubA));
    }

    private List<String> clubIdsReadBy(String token) {
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/clubs"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build(),
                String.class);
        List<String> ids = new ArrayList<>();
        for (JsonNode row : readJson(res)) {
            ids.add(row.get("id").asText());
        }
        return ids;
    }

    private static String external(UUID clubId) {
        return ClubId.of(clubId).toExternal();
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }
}
