package ch.alpenflight.multitenancy.leakage;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
abstract class CrossTenantNotFoundContract extends PostgresIntegrationTest {

    private static final String NAME_PREFIX = "IT_XTNF_";
    private static final String KEY_PREFIX = "IT_F_";

    @Autowired protected TestRestTemplate rest;
    @Autowired protected JwtTestFixture jwts;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ClubRepository clubs;
    @Autowired protected CountryRepository countries;
    @Autowired protected ClubStateRepository clubStates;

    protected UUID clubA;
    protected UUID clubB;

    @BeforeEach
    void seedTwoClubs() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, NAME_PREFIX, KEY_PREFIX);
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
        TenantTestContext.clear();
    }

    protected abstract String pathToReadById(String externalId);

    protected abstract String createUnderTenant(UUID clubId);

    protected String roleClaim() {
        return "CLUB_ADMINISTRATOR";
    }

    @Test
    void controller_get_with_other_tenant_id_returns_404() {
        String foreignId = createUnderTenant(clubA);
        String tokenAsB = jwts.mint(c -> c
                .claim("clubId", clubB.toString())
                .claim("realm_access", Map.of("roles", List.of(roleClaim()))));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenAsB);
        RequestEntity<Void> req = RequestEntity.get(pathToReadById(foreignId))
                .headers(headers)
                .build();
        ResponseEntity<String> res = rest.exchange(req, String.class);

        assertThat(res.getStatusCode())
                .as("IDOR gate is structural: cross-tenant GET on /%s must be 404, not 403",
                        pathToReadById(foreignId))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
