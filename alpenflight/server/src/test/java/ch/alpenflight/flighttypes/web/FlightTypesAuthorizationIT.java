package ch.alpenflight.flighttypes.web;

import static ch.alpenflight.flighttypes.web.FlightTypesTestFixtures.createPayload;
import static ch.alpenflight.flighttypes.web.FlightTypesTestFixtures.uniqueName;
import static ch.alpenflight.flighttypes.web.FlightTypesTestFixtures.updatePayload;
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

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class FlightTypesAuthorizationIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLUB_A = "019e30c3-2c00-7001-8000-000000000001";
    private static final String CLUB_B = "019e30c3-2c00-7001-8000-0000000ab402";

    @Autowired TestRestTemplate rest;
    @Autowired JwtTestFixture jwts;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedClubB_and_cleanFlightTypes() {
        UUID countryId = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        UUID clubStateId = jdbc.queryForObject("SELECT id FROM t_club_state LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO t_club (id, clubname, club_key, country_id, club_state_id, slug, public_registration_enabled)
                VALUES (?::uuid, ?, ?, ?::uuid, ?::uuid, ?, false)
                ON CONFLICT (id) DO NOTHING
                """,
                CLUB_B,
                "FlightType IT Test Club B",
                "FTIT_B",
                countryId.toString(),
                clubStateId.toString(),
                "flight-type-it-test-b");
        jdbc.update("DELETE FROM t_flight_type WHERE operating_club_id IN (?::uuid, ?::uuid)",
                CLUB_A, CLUB_B);
    }

    @Test
    void clubAdminOfOtherClub_seesCrossTenantFlightType_as_404_not_403() {
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/flight-types",
                createPayload(uniqueName()), adminA);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = readJson(created).get("id").asText();

        String adminB = mintToken(CLUB_B, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> read = get("/api/v1/flight-types/" + id, adminB);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> upd = put("/api/v1/flight-types/" + id,
                updatePayload(uniqueName()), adminB);
        assertThat(upd.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> del = delete("/api/v1/flight-types/" + id, adminB);
        assertThat(del.getStatusCode())
                .as("the mutation paths share the same loadOrThrow gate as the read path, "
                        + "so the IDOR contract holds for DELETE too")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void list_unauthenticated_returns_401() {
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/flight-types")).build(),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void detail_unauthenticated_returns_401() {
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.get(URI.create(
                        "/api/v1/flight-types/ft-019e30c3-2c00-7001-8000-000000000aaa"))
                        .build(),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void sysAdmin_cannotRegister_lacksClubAdminRole() {
        String sysToken = mintToken(null, "SYSTEM_ADMINISTRATOR");
        ResponseEntity<String> res = post("/api/v1/flight-types",
                createPayload(uniqueName()), sysToken);
        assertThat(res.getStatusCode())
                .as("rejected at the @PreAuthorize gate with 403 — the cross-tenant 404 contract "
                        + "applies only once the role gate has passed")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void sysAdmin_cannotUpdate_lacksClubAdminRole() {
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/flight-types",
                createPayload(uniqueName()), adminA);
        String id = readJson(created).get("id").asText();

        String sysToken = mintToken(null, "SYSTEM_ADMINISTRATOR");
        ResponseEntity<String> res = put("/api/v1/flight-types/" + id,
                updatePayload(uniqueName()), sysToken);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void sysAdmin_cannotDelete_lacksClubAdminRole() {
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/flight-types",
                createPayload(uniqueName()), adminA);
        String id = readJson(created).get("id").asText();

        String sysToken = mintToken(null, "SYSTEM_ADMINISTRATOR");
        ResponseEntity<String> res = delete("/api/v1/flight-types/" + id, sysToken);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void flightOperator_canRead_butCannotMutate() {
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/flight-types",
                createPayload(uniqueName()), adminA);
        String id = readJson(created).get("id").asText();

        String opsA = mintToken(CLUB_A, "FLIGHT_OPERATOR");
        assertThat(get("/api/v1/flight-types/" + id, opsA).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post("/api/v1/flight-types", createPayload(uniqueName()), opsA)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }


    private String mintToken(String clubId, String role) {
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
