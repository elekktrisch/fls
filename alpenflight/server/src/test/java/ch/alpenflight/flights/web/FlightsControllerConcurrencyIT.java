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
import java.util.LinkedHashMap;
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
        jdbc.update("DELETE FROM t_aircraft WHERE managing_club_id = ?::uuid AND "
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
        assertThat(body.get("type").asText())
                .isEqualTo("urn:alpenflight:problem:flight-version-mismatch");
        assertThat(body.get("expected").asLong()).isEqualTo(stale);
        assertThat(body.get("serverVersion").asLong()).isEqualTo(current);
        assertThat(body.has("actual"))
                .as("legacy 'actual' property is gone — replaced by the wire-stable serverVersion")
                .isFalse();
    }

    @Test
    void delete_withMatchingIfMatch_returns204() {
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
    void delete_withStaleIfMatch_returns412() {
        String id = readJson(post("/api/v1/flights",
                createPayload("GLIDER", aircraftIdExternal, "2026-05-01"))).get("id").asText();

        ResponseEntity<String> res = rest.exchange(
                RequestEntity.delete(URI.create("/api/v1/flights/" + id))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .header(HttpHeaders.IF_MATCH, "999")
                        .build(),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(get("/api/v1/flights/" + id).getStatusCode())
                .as("Stale precondition aborted the delete — the row is still there")
                .isEqualTo(HttpStatus.OK);
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
        jdbc.update("UPDATE t_flight SET process_state_id = ?::uuid WHERE id = ?::uuid",
                DELIVERY_BOOKED_ID, flightUuid.toString());

        ResponseEntity<String> res = rest.exchange(
                RequestEntity.delete(URI.create("/api/v1/flights/" + id))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .build(),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = readJson(res);
        assertThat(body.get("state").asText()).isEqualTo("DELIVERY_BOOKED");
    }

    @Test
    void delete_cascade_rollsBack_whenTowIsTerminal() {
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
        jdbc.update("UPDATE t_flight SET process_state_id = ?::uuid WHERE id = ?::uuid",
                DELIVERY_BOOKED_ID, towUuid.toString());

        ResponseEntity<String> del = rest.exchange(
                RequestEntity.delete(URI.create("/api/v1/flights/" + gliderId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .build(),
                String.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(get("/api/v1/flights/" + gliderId).getStatusCode())
                .as("The tow's terminal-state gate rolled the whole cascade back — the glider "
                        + "survives its own otherwise-legal delete")
                .isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/flights/" + towId).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void put_secondClient_returns412_afterFirstClientPuts() {
        String id = readJson(post("/api/v1/flights",
                createPayload("GLIDER", aircraftIdExternal, "2026-05-01"))).get("id").asText();
        long sharedVersion = readJson(get("/api/v1/flights/" + id)).get("version").asLong();

        Map<String, Object> firstBody = updatePayload();
        firstBody.put("comment", "first writer changes a field so the save is not a no-op");
        ResponseEntity<String> first = rest.exchange(
                RequestEntity.put(URI.create("/api/v1/flights/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .header(HttpHeaders.IF_MATCH, String.valueOf(sharedVersion))
                        .body(firstBody),
                String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> secondBody = updatePayload();
        secondBody.put("comment", "second writer changes a field so the save is not a no-op");
        ResponseEntity<String> second = rest.exchange(
                RequestEntity.put(URI.create("/api/v1/flights/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .header(HttpHeaders.IF_MATCH, String.valueOf(sharedVersion))
                        .body(secondBody),
                String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        JsonNode body = readJson(second);
        assertThat(body.get("serverVersion").asLong())
                .as("The first PUT dirtied a field, so Hibernate bumped @Version — the second "
                        + "client's copy of sharedVersion is now stale")
                .isEqualTo(sharedVersion + 1);
    }

    private Map<String, Object> updatePayload() {
        Map<String, Object> body = new LinkedHashMap<>();
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
