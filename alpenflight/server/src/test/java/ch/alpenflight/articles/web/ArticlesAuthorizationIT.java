package ch.alpenflight.articles.web;

import static ch.alpenflight.articles.web.ArticlesTestFixtures.createPayload;
import static ch.alpenflight.articles.web.ArticlesTestFixtures.uniqueNumber;
import static ch.alpenflight.articles.web.ArticlesTestFixtures.updatePayload;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
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
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Authz matrix for the Article REST surface under the S-159 model:
 *
 * <ul>
 *   <li><strong>Tenant scoping is structural</strong> via Hibernate's
 *       {@code @TenantId} discriminator on {@code Article.operatingClubId}.
 *       Cross-club access is invisible (404), not 403 — the IDOR contract.</li>
 *   <li><strong>Role gates</strong> on the controller: CLUB_ADMINISTRATOR
 *       for register / update / soft-delete; reads open to any authenticated
 *       principal so the future Flight / DeliveryItem picker can fetch the
 *       catalogue without an elevated role.</li>
 *   <li><strong>SYSTEM_ADMINISTRATOR has no rights here</strong> — sysadmin
 *       lacks a tenant context (no clubId claim) and is denied at the role
 *       gate on every write (403, not 404).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class ArticlesAuthorizationIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLUB_A = "019e30c3-2c00-7001-8000-000000000001";
    private static final String CLUB_B = "019e30c3-2c00-7001-8000-000000000002";

    @Autowired TestRestTemplate rest;
    @Autowired JwtTestFixture jwts;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedClubB_and_cleanArticles() {
        UUID countryId = jdbc.queryForObject("SELECT id FROM country LIMIT 1", UUID.class);
        UUID clubStateId = jdbc.queryForObject("SELECT id FROM club_state LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO club (id, clubname, club_key, country_id, club_state_id, slug, public_registration_enabled)
                VALUES (?::uuid, ?, ?, ?::uuid, ?::uuid, ?, false)
                ON CONFLICT (id) DO NOTHING
                """,
                CLUB_B,
                "Articles IT Test Club B",
                "ART_B",
                countryId.toString(),
                clubStateId.toString(),
                "articles-it-test-b");
        jdbc.update("DELETE FROM article WHERE operating_club_id IN (?::uuid, ?::uuid)",
                CLUB_A, CLUB_B);
    }

    @Test
    void clubAdminOfOtherClub_seesCrossTenantArticle_as_404_not_403() {
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/articles",
                createPayload(uniqueNumber()), adminA);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = readJson(created).get("id").asText();

        String adminB = mintToken(CLUB_B, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> read = get("/api/v1/articles/" + id, adminB);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> upd = put("/api/v1/articles/" + id,
                updatePayload(uniqueNumber()), adminB);
        assertThat(upd.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> del = delete("/api/v1/articles/" + id, adminB);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void list_unauthenticated_returns_401() {
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/articles")).build(),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void detail_unauthenticated_returns_401() {
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.get(URI.create(
                        "/api/v1/articles/art-019e30c3-2c00-7001-8000-000000000aaa"))
                        .build(),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void sysAdmin_cannotRegister_lacksClubAdminRole() {
        String sysToken = mintToken(null, "SYSTEM_ADMINISTRATOR");
        ResponseEntity<String> res = post("/api/v1/articles",
                createPayload(uniqueNumber()), sysToken);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void sysAdmin_cannotUpdate_lacksClubAdminRole() {
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/articles",
                createPayload(uniqueNumber()), adminA);
        String id = readJson(created).get("id").asText();

        String sysToken = mintToken(null, "SYSTEM_ADMINISTRATOR");
        ResponseEntity<String> res = put("/api/v1/articles/" + id,
                updatePayload(uniqueNumber()), sysToken);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void sysAdmin_cannotDelete_lacksClubAdminRole() {
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/articles",
                createPayload(uniqueNumber()), adminA);
        String id = readJson(created).get("id").asText();

        String sysToken = mintToken(null, "SYSTEM_ADMINISTRATOR");
        ResponseEntity<String> res = delete("/api/v1/articles/" + id, sysToken);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void flightOperator_canRead_butCannotMutate() {
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/articles",
                createPayload(uniqueNumber()), adminA);
        String id = readJson(created).get("id").asText();

        String opsA = mintToken(CLUB_A, "FLIGHT_OPERATOR");
        assertThat(get("/api/v1/articles/" + id, opsA).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post("/api/v1/articles", createPayload(uniqueNumber()), opsA)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ----- helpers -----

    private String mintToken(@Nullable String clubId, String role) {
        Consumer<com.nimbusds.jwt.JWTClaimsSet.Builder> body = c -> {
            if (clubId != null) {
                c.claim("clubId", clubId);
            }
            c.claim("realm_access", Map.of("roles", List.of(role)));
        };
        return jwts.mint(body);
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(
                RequestEntity.get(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build(),
                String.class);
    }

    private ResponseEntity<String> post(String path, Object body, String token) {
        return rest.exchange(
                RequestEntity.post(URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .body(body),
                String.class);
    }

    private ResponseEntity<String> put(String path, Object body, String token) {
        return rest.exchange(
                RequestEntity.put(URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .body(body),
                String.class);
    }

    private ResponseEntity<String> delete(String path, String token) {
        return rest.exchange(
                RequestEntity.delete(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build(),
                String.class);
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }
}
