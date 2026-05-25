package ch.alpenflight.flights.web;

import static ch.alpenflight.flights.web.FlightsTestFixtures.cleanFlightRowsFor;
import static ch.alpenflight.flights.web.FlightsTestFixtures.createPayload;
import static ch.alpenflight.flights.web.FlightsTestFixtures.seedAircraftFor;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
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

/**
 * Integration tests for the Glider↔Tow link (S-063). Covers the depth
 * around {@code towFlightId} the smaller ITs don't reach: partial-PUT
 * preservation, explicit-null unlink, re-link orphaning, double-link
 * rejection, cross-tenant tow invisibility, and cascade-with-admin.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class FlightsTowLinkIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID CLUB_A = UUID.fromString("019e30c3-2c00-7001-8000-0000000000a1");
    private static final UUID CLUB_B = UUID.fromString("019e30c3-2c00-7001-8000-0000000000a2");
    private static final String NAME_PREFIX = "IT_FTL_";
    private static final String KEY_PREFIX = "IT_FL";
    private static final String EXCLUDED_FROM_DELIVERY_ID =
            "019e2e15-2c00-7a9f-8000-000000003a9f";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    private String tokenA;
    private String aircraftA;

    @BeforeEach
    void seed() {
        cleanFlightRowsFor(jdbc, CLUB_A, CLUB_B);
        new TwoClubFixture(jdbc, CLUB_A, CLUB_B, NAME_PREFIX, KEY_PREFIX).seed();
        tokenA = mintAdminToken(CLUB_A);
        aircraftA = "ac-" + seedAircraftFor(jdbc, CLUB_A);
    }

    @Test
    void put_without_towFlightId_preserves_existing_link() {
        String gliderId = createFlight("GLIDER");
        String towId = createFlight("TOW");
        link(gliderId, towId);

        // Omit towFlightId entirely on the PUT. Absence means preserve, not unlink.
        Map<String, Object> body = updateBody();
        body.put("comment", "edited the crew note");
        ResponseEntity<String> put = put("/api/v1/flights/" + gliderId, body, tokenA);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode detail = readJson(get("/api/v1/flights/" + gliderId, tokenA));
        assertThat(detail.get("towFlightId").asText())
                .as("Omitting towFlightId on partial PUT must NOT unlink the tow")
                .isEqualTo(towId);
    }

    @Test
    void put_with_unlinkTowFlight_true_unlinks() {
        // Per the partial-PUT contract, plain absence of `towFlightId` means
        // "preserve" — explicit detach without delete uses the new
        // `unlinkTowFlight` boolean.
        String gliderId = createFlight("GLIDER");
        String towId = createFlight("TOW");
        link(gliderId, towId);

        Map<String, Object> body = updateBody();
        body.put("unlinkTowFlight", true);
        ResponseEntity<String> put = put("/api/v1/flights/" + gliderId, body, tokenA);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode detail = readJson(get("/api/v1/flights/" + gliderId, tokenA));
        assertThat(hasTowLink(detail)).isFalse();
        assertThat(get("/api/v1/flights/" + towId, tokenA).getStatusCode())
                .as("Unlink does not delete the tow row")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void put_relink_to_different_tow_leaves_old_tow_orphan() {
        String gliderId = createFlight("GLIDER");
        String tow1 = createFlight("TOW");
        String tow2 = createFlight("TOW");
        link(gliderId, tow1);
        link(gliderId, tow2);

        JsonNode detail = readJson(get("/api/v1/flights/" + gliderId, tokenA));
        assertThat(detail.get("towFlightId").asText()).isEqualTo(tow2);
        // Old tow row remains intact — re-link does NOT auto-cleanup the
        // previous tow (legacy parity; orphan sweep is a future story).
        assertThat(get("/api/v1/flights/" + tow1, tokenA).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void put_linking_tow_already_referenced_by_another_glider_rejects() {
        String tow = createFlight("TOW");
        String glider1 = createFlight("GLIDER");
        String glider2 = createFlight("GLIDER");
        link(glider1, tow);

        Map<String, Object> body = updateBody();
        body.put("towFlightId", tow);
        ResponseEntity<String> put = put("/api/v1/flights/" + glider2, body, tokenA);

        assertThat(put.getStatusCode())
                .as("Two gliders may not share one tow — cascade-delete of glider1 "
                        + "would otherwise silently orphan the link from glider2.")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // glider2 unchanged; glider1's link intact.
        JsonNode g2 = readJson(get("/api/v1/flights/" + glider2, tokenA));
        assertThat(hasTowLink(g2)).isFalse();
        JsonNode g1 = readJson(get("/api/v1/flights/" + glider1, tokenA));
        assertThat(g1.get("towFlightId").asText()).isEqualTo(tow);
    }

    @Test
    void put_linking_cross_tenant_tow_is_invisible_and_rejected() {
        // Tow belongs to Club B. Club A's PUT can't see it — @TenantId hides
        // the row, so the lookup fails and the link is rejected. Mirrors the
        // IDOR-as-404 contract on read paths.
        String tokenB = mintAdminToken(CLUB_B);
        String aircraftB = "ac-" + seedAircraftFor(jdbc, CLUB_B);
        String towB = readJson(post("/api/v1/flights",
                payload("TOW", aircraftB, "2026-05-01"), tokenB)).get("id").asText();

        String gliderA = createFlight("GLIDER");
        Map<String, Object> body = updateBody();
        body.put("towFlightId", towB);
        ResponseEntity<String> put = put("/api/v1/flights/" + gliderA, body, tokenA);

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode glider = readJson(get("/api/v1/flights/" + gliderA, tokenA));
        assertThat(hasTowLink(glider)).isFalse();
    }

    @Test
    void delete_cascades_when_glider_in_excluded_state_and_caller_is_admin() {
        // EXCLUDED_FROM_DELIVERY_PROCESS is admin-locked. assertMutationAllowed
        // gates on the caller's role; club-admin passes through, the cascade
        // fires, both rows are soft-deleted.
        String gliderId = createFlight("GLIDER");
        String towId = createFlight("TOW");
        link(gliderId, towId);

        UUID gliderUuid = UUID.fromString(gliderId.substring(3));
        jdbc.update("UPDATE flight SET process_state_id = ?::uuid WHERE id = ?::uuid",
                EXCLUDED_FROM_DELIVERY_ID, gliderUuid.toString());

        ResponseEntity<String> del = delete("/api/v1/flights/" + gliderId, tokenA);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Both rows soft-deleted.
        assertThat(get("/api/v1/flights/" + gliderId, tokenA).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/flights/" + towId, tokenA).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String createFlight(String type) {
        return readJson(post("/api/v1/flights",
                payload(type, aircraftA, "2026-05-01"), tokenA)).get("id").asText();
    }

    private void link(String gliderId, String towId) {
        Map<String, Object> body = updateBody();
        body.put("towFlightId", towId);
        ResponseEntity<String> res = put("/api/v1/flights/" + gliderId, body, tokenA);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Map<String, Object> updateBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("aircraftId", aircraftA);
        body.put("flightDate", "2026-05-01");
        body.put("isSoloFlight", false);
        body.put("noStartTimeInformation", false);
        body.put("noLdgTimeInformation", false);
        body.put("crew", List.of());
        return body;
    }

    private static Map<String, Object> payload(String type, String aircraftIdExternal,
                                               String flightDate) {
        return createPayload(type, aircraftIdExternal, flightDate);
    }

    private String mintAdminToken(UUID clubId) {
        return jwts.mint(c -> c
                .claim("clubId", clubId.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
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

    private static boolean hasTowLink(JsonNode detail) {
        return detail.has("towFlightId") && !detail.get("towFlightId").isNull();
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }
}
