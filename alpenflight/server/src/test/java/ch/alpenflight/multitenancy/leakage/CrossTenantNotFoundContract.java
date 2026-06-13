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

/**
 * Abstract MockMvc / TestRestTemplate base future tenant-scoped controllers
 * extend to inherit the IDOR-gate witness. Concrete subclasses:
 *
 * <ul>
 *   <li>Implement {@link #pathToReadById(String)} for their {@code GET /…/{id}}
 *       endpoint.</li>
 *   <li>Implement {@link #createUnderTenant(UUID)} returning the new row's
 *       external id (string-form, ready to slot into the path).</li>
 *   <li>Optionally override {@link #roleClaim()} when the path needs more
 *       than a vanilla authenticated user (the default supplies the broadest
 *       role; the controller's own authz tests cover the role matrix).</li>
 * </ul>
 *
 * <p>Per ADR 0008 the leakage surface is a 404 — never a 403 — because a row
 * the caller cannot reach must look identical to a row that does not exist.
 * A 403 would leak existence.
 *
 * <p>The base is package-private so only the leakage package can extend it,
 * keeping the IDOR-gate surface concentrated.
 */
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

    /**
     * Minted two-club ids; subclass-shared. Captured after the production-create
     * fixture seeds (J-26 T-19) — no longer pinned constants.
     */
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

    /**
     * Builds the path string for {@code GET /…/{externalId}}; e.g.
     * {@code "/api/v1/locations/" + externalId}.
     */
    protected abstract String pathToReadById(String externalId);

    /**
     * Persists a tenant-scoped row under {@code clubId}; returns its
     * external-form id (the form the path variable accepts).
     */
    protected abstract String createUnderTenant(UUID clubId);

    /**
     * Returns the role to mint into the caller's token. Default
     * {@code CLUB_ADMINISTRATOR} satisfies the broad write surface while
     * staying single-realm — subclasses override for narrower roles.
     */
    protected String roleClaim() {
        return "CLUB_ADMINISTRATOR";
    }

    @Test
    void controller_get_with_other_tenant_id_returns_404() {
        // clubA creates a row; clubB's authenticated user fetches its
        // external id and must receive 404 (not 403 — existence is not
        // leaked).
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
