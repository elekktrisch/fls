package ch.alpenflight.locations.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.id.LocationId;
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

/**
 * Aggregate-internal lifecycle test for {@code InOutboundPoint}: create with
 * N children, update swaps the list, delete cascades. Proves the
 * {@code orphanRemoval=true} contract on the {@code Location.inOutboundPoints}
 * mapping at full HTTP-layer fidelity.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class LocationsInOutboundPointsIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    private String sysadminToken;

    @BeforeEach
    void mintSysadminToken() {
        sysadminToken = jwts.mint(c -> c
                .claim("realm_access", Map.of("roles", List.of("SYSTEM_ADMINISTRATOR"))));
    }

    @Test
    void create_withThreePoints_persists_threeRows() {
        Map<String, Object> payload = LocationsControllerIT.createPayload(
                "Airfield with IOPs " + LocationsControllerIT.suffix(),
                LocationsControllerIT.uniqueIcao());
        payload.put("inOutboundPoints", List.of(
                iop("North entry", "INBOUND", "N"),
                iop("South entry", "INBOUND", "S"),
                iop("West exit", "OUTBOUND", "W")));

        ResponseEntity<String> res = post("/api/v1/locations", payload);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = readJson(res);
        assertThat(body.get("inOutboundPoints").size()).isEqualTo(3);

        UUID raw = LocationId.parse(body.get("id").asText()).value();
        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM inoutbound_point WHERE location_id = ?::uuid",
                Integer.class, raw.toString());
        assertThat(rowCount).isEqualTo(3);
    }

    @Test
    void update_swapsThePointList_andOrphansAreRemoved() {
        Map<String, Object> payload = LocationsControllerIT.createPayload(
                "IOP Swap " + LocationsControllerIT.suffix(),
                LocationsControllerIT.uniqueIcao());
        payload.put("inOutboundPoints", List.of(
                iop("Original A", "INBOUND", "N"),
                iop("Original B", "INBOUND", "S")));
        ResponseEntity<String> created = post("/api/v1/locations", payload);
        String externalId = readJson(created).get("id").asText();
        UUID raw = LocationId.parse(externalId).value();

        // Sanity: the parent still sees its persisted children.
        ResponseEntity<String> reread = get("/api/v1/locations/" + externalId);
        assertThat(readJson(reread).get("inOutboundPoints").size()).isEqualTo(2);

        Map<String, Object> upd = LocationsControllerIT.updatePayload(
                readJson(reread).get("locationName").asText(),
                readJson(reread).get("icaoCode").asText());
        upd.put("inOutboundPoints", List.of(iop("Replacement C", "OUTBOUND", "W")));
        ResponseEntity<String> updated = put("/api/v1/locations/" + externalId, upd);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);

        Integer afterUpdate = jdbc.queryForObject(
                "SELECT count(*) FROM inoutbound_point WHERE location_id = ?::uuid",
                Integer.class, raw.toString());
        assertThat(afterUpdate)
                .as("Orphan-removal must drop the two original points; only the one replacement remains")
                .isEqualTo(1);

        JsonNode body = readJson(updated);
        assertThat(body.get("inOutboundPoints").size()).isEqualTo(1);
        assertThat(body.get("inOutboundPoints").get(0).get("pointName").asText())
                .isEqualTo("Replacement C");
    }

    @Test
    void update_emptyList_clearsAllPoints() {
        Map<String, Object> payload = LocationsControllerIT.createPayload(
                "Cleared IOPs " + LocationsControllerIT.suffix(),
                LocationsControllerIT.uniqueIcao());
        payload.put("inOutboundPoints", List.of(
                iop("To be removed", "INBOUND", "N")));
        ResponseEntity<String> created = post("/api/v1/locations", payload);
        String externalId = readJson(created).get("id").asText();
        UUID raw = LocationId.parse(externalId).value();

        Map<String, Object> upd = LocationsControllerIT.updatePayload(
                readJson(created).get("locationName").asText(),
                readJson(created).get("icaoCode").asText());
        upd.put("inOutboundPoints", List.of());
        put("/api/v1/locations/" + externalId, upd);

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM inoutbound_point WHERE location_id = ?::uuid",
                Integer.class, raw.toString());
        assertThat(count).isZero();
    }

    @Test
    void softDeletedLocation_returns_404_evenIfChildPointsExistInDB() {
        Map<String, Object> payload = LocationsControllerIT.createPayload(
                "Soft del with IOPs " + LocationsControllerIT.suffix(),
                LocationsControllerIT.uniqueIcao());
        payload.put("inOutboundPoints", List.of(
                iop("Persists", "INBOUND", "N")));
        ResponseEntity<String> created = post("/api/v1/locations", payload);
        String externalId = readJson(created).get("id").asText();
        UUID raw = LocationId.parse(externalId).value();

        rest.exchange(authed(
                        RequestEntity.delete(URI.create("/api/v1/locations/" + externalId))).build(),
                Void.class);

        // The Location is soft-deleted; GET must return 404. Child rows
        // remain in the DB (ON DELETE CASCADE is for HARD delete; soft only
        // flips the flag on the parent).
        ResponseEntity<String> getAfter = get("/api/v1/locations/" + externalId);
        assertThat(getAfter.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        Integer iopCount = jdbc.queryForObject(
                "SELECT count(*) FROM inoutbound_point WHERE location_id = ?::uuid",
                Integer.class, raw.toString());
        assertThat(iopCount).isEqualTo(1);
    }

    // ----- helpers -----

    private static Map<String, Object> iop(String name, String pointType, String direction) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("pointName", name);
        n.put("pointType", pointType);
        n.put("direction", direction);
        return n;
    }

    private ResponseEntity<String> get(String path) {
        return rest.exchange(authed(
                        RequestEntity.get(URI.create(path))).build(),
                String.class);
    }

    private ResponseEntity<String> post(String path, Map<String, Object> body) {
        return rest.exchange(authed(
                        RequestEntity.post(URI.create(path))
                                .contentType(MediaType.APPLICATION_JSON))
                        .body(body),
                String.class);
    }

    private ResponseEntity<String> put(String path, Map<String, Object> body) {
        return rest.exchange(authed(
                        RequestEntity.put(URI.create(path))
                                .contentType(MediaType.APPLICATION_JSON))
                        .body(body),
                String.class);
    }

    private <T extends RequestEntity.BodyBuilder> T authed(T builder) {
        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + sysadminToken);
        return builder;
    }

    private RequestEntity.HeadersBuilder<?> authed(RequestEntity.HeadersBuilder<?> builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + sysadminToken);
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse: " + res.getBody(), e);
        }
    }
}
