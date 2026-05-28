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
 * Full-stack HTTP integration test for the FlightType CRUD slice under a
 * CLUB_ADMINISTRATOR principal of seed-club-1. Asserts the REST surface,
 * soft-delete + recreate-same-name, and per-tenant name uniqueness.
 *
 * <p>Tenant isolation lives in {@link FlightTypesTenantIsolationIT}; role
 * authz matrix (404-not-403, sysadmin denial) lives in
 * {@link FlightTypesAuthorizationIT}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class FlightTypesControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLUB_ID = "019e30c3-2c00-7001-8000-000000000001";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    private String clubAdminToken;

    @BeforeEach
    void mintClubAdminTokenAndCleanFlightTypes() {
        clubAdminToken = jwts.mint(c -> c
                .claim("clubId", CLUB_ID)
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
        // Per-test cleanup keeps the seed-club's slot empty so each test
        // starts with a known surface (the V3 seed doesn't insert
        // flight_type rows; only V3-seeded FCBTs).
        jdbc.update("DELETE FROM t_flight_type WHERE operating_club_id = ?::uuid", CLUB_ID);
    }

    @Test
    void listFlightTypes_returns_200_with_empty_array_initially() {
        ResponseEntity<String> res = get("/api/v1/flight-types");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isZero();
    }

    @Test
    void registerFlightType_valid_returns_201_with_location_header() {
        String name = uniqueName();
        ResponseEntity<String> res = post("/api/v1/flight-types", createPayload(name));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = readJson(res);
        assertThat(body.get("flightTypeName").asText()).isEqualTo(name);
        assertThat(body.get("isForGliderFlights").asBoolean()).isTrue();
        URI loc = res.getHeaders().getLocation();
        assertThat(loc).isNotNull();
        assertThat(loc.getPath()).isEqualTo("/api/v1/flight-types/" + body.get("id").asText());
    }

    @Test
    void registerFlightType_duplicateName_returns_409() {
        String name = uniqueName();
        ResponseEntity<String> first = post("/api/v1/flight-types", createPayload(name));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> dup = post("/api/v1/flight-types", createPayload(name));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = readJson(dup);
        assertThat(body.get("title").asText()).contains("already in use");
        assertThat(body.get("field").asText()).isEqualTo("flightTypeName");
    }

    @Test
    void updateFlightType_renames_andFlipsFlags() {
        String original = uniqueName();
        ResponseEntity<String> created = post("/api/v1/flight-types", createPayload(original));
        String id = readJson(created).get("id").asText();

        String renamed = uniqueName();
        ResponseEntity<String> upd = put("/api/v1/flight-types/" + id, updatePayload(renamed));
        assertThat(upd.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(upd);
        assertThat(body.get("flightTypeName").asText()).isEqualTo(renamed);
        assertThat(body.get("isInstructorRequired").asBoolean()).isFalse();
        assertThat(body.get("isPassengerFlight").asBoolean()).isTrue();
    }

    @Test
    void softDelete_thenRecreateSameName_succeeds() {
        // Mirrors the S-050 Aircraft pattern — the partial-UNIQUE filters
        // soft-deleted rows out of the uniqueness scope.
        String name = uniqueName();
        ResponseEntity<String> created = post("/api/v1/flight-types", createPayload(name));
        String id = readJson(created).get("id").asText();

        ResponseEntity<String> del = delete("/api/v1/flight-types/" + id);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> recreated = post("/api/v1/flight-types", createPayload(name));
        assertThat(recreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void registerFlightType_minSeatsZero_returns_400() {
        // DTO @Min(1) rejects 0 at the boundary; the legacy 0-treated-as-null
        // ambiguity is documented as a parity exclusion on S-053.
        Map<String, Object> body = createPayload(uniqueName());
        body.put("minNrOfAircraftSeatsRequired", 0);
        ResponseEntity<String> res = post("/api/v1/flight-types", body);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getFlightType_unknownId_returns_404() {
        ResponseEntity<String> res = get(
                "/api/v1/flight-types/ft-019e30c3-2c00-7001-8000-000000000aaa");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ----- helpers -----

    private ResponseEntity<String> get(String path) {
        return rest.exchange(
                RequestEntity.get(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .build(),
                String.class);
    }

    private ResponseEntity<String> post(String path, Object body) {
        return rest.exchange(
                RequestEntity.post(URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .body(body),
                String.class);
    }

    private ResponseEntity<String> put(String path, Object body) {
        return rest.exchange(
                RequestEntity.put(URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .body(body),
                String.class);
    }

    private ResponseEntity<String> delete(String path) {
        return rest.exchange(
                RequestEntity.delete(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
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
