package ch.alpenflight.flights.web;

import static ch.alpenflight.flights.web.FlightsTestFixtures.cleanFlightRowsFor;
import static ch.alpenflight.flights.web.FlightsTestFixtures.createPayload;
import static ch.alpenflight.flights.web.FlightsTestFixtures.seedAircraftFor;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Concurrency-contract integration tests for the Flight slice.
 *
 * <p>Covers what {@link FlightsControllerIT} doesn't: the 412 ProblemDetail
 * body shape (`expected` / `actual` / `serverVersion` properties), DELETE
 * If-Match symmetry, the two-client race window, and the 409-vs-412
 * distinction when a delete hits a {@code DELIVERY_BOOKED} terminal state.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class FlightsControllerConcurrencyIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLUB_ID = "019e30c3-2c00-7001-8000-000000000001";
    private static final UUID CLUB_UUID = UUID.fromString(CLUB_ID);
    private static final String DELIVERY_BOOKED_ID = "019e2e15-2c00-7a9e-8000-000000003a9e";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    private String clubAdminToken;
    private String aircraftIdExternal;

    @BeforeEach
    void seedAndAuth() {
        clubAdminToken = jwts.mint(c -> c
                .claim("clubId", CLUB_ID)
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
        cleanFlightRowsFor(jdbc, CLUB_UUID);
        jdbc.update("DELETE FROM aircraft WHERE managing_club_id = ?::uuid AND "
                + "immatriculation LIKE 'HB-FT%'", CLUB_ID);
        UUID aid = seedAircraftFor(jdbc, CLUB_UUID);
        aircraftIdExternal = "ac-" + aid;
    }

    @Test
    void put_returns412WithProblemDetailCarryingServerVersion_whenIfMatchStale() {
        String id = readJson(post("/api/v1/flights",
                createPayload("GLIDER", aircraftIdExternal, "2026-05-01"))).get("id").asText();
        long current = readJson(get("/api/v1/flights/" + id)).get("version").asLong();
        long stale = current + 999;

        ResponseEntity<String> res = rest.exchange(
                RequestEntity.put(URI.create("/api/v1/flights/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .header(HttpHeaders.IF_MATCH, String.valueOf(stale))
                        .body(updatePayload()),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(res.getHeaders().getContentType().toString())
                .isEqualTo("application/problem+json");
        JsonNode body = readJson(res);
        assertThat(body.get("type").asText()).contains("flight-version-mismatch");
        assertThat(body.get("expected").asLong()).isEqualTo(stale);
        assertThat(body.get("actual").asLong()).isEqualTo(current);
        assertThat(body.get("serverVersion").asLong()).isEqualTo(current);
    }

    @Test
    void delete_withMatchingIfMatch_returns_204() {
        String id = readJson(post("/api/v1/flights",
                createPayload("GLIDER", aircraftIdExternal, "2026-05-01"))).get("id").asText();
        long version = readJson(get("/api/v1/flights/" + id)).get("version").asLong();

        ResponseEntity<String> res = rest.exchange(
                RequestEntity.delete(URI.create("/api/v1/flights/" + id))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .header(HttpHeaders.IF_MATCH, String.valueOf(version))
                        .build(),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void delete_withStaleIfMatch_returns_412() {
        String id = readJson(post("/api/v1/flights",
                createPayload("GLIDER", aircraftIdExternal, "2026-05-01"))).get("id").asText();

        ResponseEntity<String> res = rest.exchange(
                RequestEntity.delete(URI.create("/api/v1/flights/" + id))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .header(HttpHeaders.IF_MATCH, "999")
                        .build(),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        // Row must still be present — stale precondition aborted the delete.
        assertThat(get("/api/v1/flights/" + id).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void delete_withWildcardIfMatch_treatsAsNoPrecondition() {
        String id = readJson(post("/api/v1/flights",
                createPayload("GLIDER", aircraftIdExternal, "2026-05-01"))).get("id").asText();

        ResponseEntity<String> res = rest.exchange(
                RequestEntity.delete(URI.create("/api/v1/flights/" + id))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .header(HttpHeaders.IF_MATCH, "*")
                        .build(),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void delete_returns409_andLeavesRow_whenProcessStateDeliveryBooked() {
        String id = readJson(post("/api/v1/flights",
                createPayload("GLIDER", aircraftIdExternal, "2026-05-01"))).get("id").asText();
        UUID flightUuid = UUID.fromString(id.substring(3));
        jdbc.update("UPDATE flight SET process_state_id = ?::uuid WHERE id = ?::uuid",
                DELIVERY_BOOKED_ID, flightUuid.toString());

        ResponseEntity<String> res = rest.exchange(
                RequestEntity.delete(URI.create("/api/v1/flights/" + id))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .build(),
                String.class);

        // FlightStateGateException.TERMINAL → 409, distinct from the 412 path.
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = readJson(res);
        assertThat(body.get("state").asText()).isEqualTo("DELIVERY_BOOKED");
    }

    @Test
    void delete_cascade_rollsBack_whenTowIsTerminal() {
        // Glider is fine to delete; tow is DELIVERY_BOOKED. Cascade must
        // honour the gate on the tow, throwing — which rolls back the
        // glider delete too thanks to the class-level @Transactional.
        String gliderId = readJson(post("/api/v1/flights",
                createPayload("GLIDER", aircraftIdExternal, "2026-05-01"))).get("id").asText();
        String towId = readJson(post("/api/v1/flights",
                createPayload("TOW", aircraftIdExternal, "2026-05-01"))).get("id").asText();
        Map<String, Object> link = updatePayload();
        link.put("towFlightId", towId);
        ResponseEntity<String> linkRes = rest.exchange(
                RequestEntity.put(URI.create("/api/v1/flights/" + gliderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .body(link),
                String.class);
        assertThat(linkRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID towUuid = UUID.fromString(towId.substring(3));
        jdbc.update("UPDATE flight SET process_state_id = ?::uuid WHERE id = ?::uuid",
                DELIVERY_BOOKED_ID, towUuid.toString());

        ResponseEntity<String> del = rest.exchange(
                RequestEntity.delete(URI.create("/api/v1/flights/" + gliderId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .build(),
                String.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Neither row was soft-deleted — transaction rolled back.
        assertThat(get("/api/v1/flights/" + gliderId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/flights/" + towId).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void put_secondClient_returns412_afterFirstClientPuts() {
        // Two clients load the same flight; first PUT succeeds and bumps
        // version; second PUT with the now-stale version returns 412.
        // Each PUT must mutate at least one field so Hibernate's dirty-check
        // produces an UPDATE (no-op saves don't bump @Version).
        String id = readJson(post("/api/v1/flights",
                createPayload("GLIDER", aircraftIdExternal, "2026-05-01"))).get("id").asText();
        long sharedVersion = readJson(get("/api/v1/flights/" + id)).get("version").asLong();

        Map<String, Object> firstBody = updatePayload();
        firstBody.put("comment", "first writer");
        ResponseEntity<String> first = rest.exchange(
                RequestEntity.put(URI.create("/api/v1/flights/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .header(HttpHeaders.IF_MATCH, String.valueOf(sharedVersion))
                        .body(firstBody),
                String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> secondBody = updatePayload();
        secondBody.put("comment", "second writer");
        ResponseEntity<String> second = rest.exchange(
                RequestEntity.put(URI.create("/api/v1/flights/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .header(HttpHeaders.IF_MATCH, String.valueOf(sharedVersion))
                        .body(secondBody),
                String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        JsonNode body = readJson(second);
        assertThat(body.get("serverVersion").asLong()).isEqualTo(sharedVersion + 1);
    }

    private Map<String, Object> updatePayload() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("aircraftId", aircraftIdExternal);
        body.put("flightDate", "2026-05-01");
        body.put("isSoloFlight", false);
        body.put("noStartTimeInformation", false);
        body.put("noLdgTimeInformation", false);
        body.put("crew", List.of());
        return body;
    }

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

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }
}
