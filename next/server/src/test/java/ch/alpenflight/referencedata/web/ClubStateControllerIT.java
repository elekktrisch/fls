package ch.alpenflight.referencedata.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/**
 * Full-stack HTTP integration test for the ClubState reference-data read
 * surface. Exercises the V2-seeded {@code club_state} catalog (ACTIVE,
 * SUSPENDED, etc.) through the production filter chain.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class ClubStateControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired TestRestTemplate rest;
    @Autowired JwtTestFixture jwts;

    private String userToken;

    @BeforeEach
    void mintToken() {
        userToken = jwts.mint(c -> c
                .claim("realm_access", Map.of("roles", List.of("FLIGHT_OPERATOR"))));
    }

    @Test
    void listClubStates_returns_200_with_seeded_rows() {
        ResponseEntity<String> res = get("/api/v1/club-states");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.isArray()).isTrue();
        assertThat(body.size())
                .as("V2 seeds at least one club_state row")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void listClubStates_payload_carries_id_code_name() {
        ResponseEntity<String> res = get("/api/v1/club-states");
        JsonNode first = readJson(res).get(0);
        assertThat(first.get("id").asText()).matches("[0-9a-f-]{36}");
        assertThat(first.has("code")).isTrue();
        assertThat(first.has("name")).isTrue();
    }

    @Test
    void listClubStates_anonymous_returns_401() {
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/club-states")).build(),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void listClubStates_returns_identical_rows_under_two_different_tenant_claims() {
        String tokenA = jwts.mint(c -> c
                .claim("clubId", "019e30c3-2c00-7001-8000-0000000000a1")
                .claim("realm_access", Map.of("roles", List.of("FLIGHT_OPERATOR"))));
        String tokenB = jwts.mint(c -> c
                .claim("clubId", "019e30c3-2c00-7001-8000-0000000000a2")
                .claim("realm_access", Map.of("roles", List.of("FLIGHT_OPERATOR"))));

        ResponseEntity<String> rA = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/club-states"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA).build(),
                String.class);
        ResponseEntity<String> rB = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/club-states"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB).build(),
                String.class);

        List<String> idsA = new ArrayList<>();
        readJson(rA).forEach(n -> idsA.add(n.get("id").asText()));
        List<String> idsB = new ArrayList<>();
        readJson(rB).forEach(n -> idsB.add(n.get("id").asText()));
        assertThat(idsA)
                .as("ClubState reads must surface the IDENTICAL row set across tenant claims")
                .containsExactlyElementsOf(idsB);
    }

    private ResponseEntity<String> get(String path) {
        return rest.exchange(
                RequestEntity.get(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
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
