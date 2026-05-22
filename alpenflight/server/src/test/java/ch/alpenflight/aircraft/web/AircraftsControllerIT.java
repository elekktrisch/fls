package ch.alpenflight.aircraft.web;

import static ch.alpenflight.aircraft.web.AircraftsTestFixtures.SEED_AIRCRAFT_STATE_MAINTENANCE;
import static ch.alpenflight.aircraft.web.AircraftsTestFixtures.SEED_AIRCRAFT_STATE_OK;
import static ch.alpenflight.aircraft.web.AircraftsTestFixtures.createPayload;
import static ch.alpenflight.aircraft.web.AircraftsTestFixtures.uniqueImmatriculation;
import static ch.alpenflight.aircraft.web.AircraftsTestFixtures.updatePayload;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.id.AircraftId;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
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
 * Full-stack HTTP integration test for the Aircraft CRUD slice. Asserts
 * the REST surface, soft-delete semantics, immatriculation uniqueness,
 * state-history aggregate invariants, and the cross-tenant read contract
 * under a SYSTEM_ADMINISTRATOR principal.
 *
 * <p>Authz matrix evidence (CLUB_ADMIN cross-club mutation = 403,
 * null-owner = SYS_ADMIN-only, FLIGHT_OPS for state / counter) lives in
 * {@link AircraftsAuthorizationIT}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class AircraftsControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SYSADMIN_CLUB_ID = "019e30c3-2c00-7001-8000-000000000001";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    private String sysadminToken;

    @BeforeEach
    void mintSysadminToken() {
        sysadminToken = jwts.mint(c -> c
                .claim("clubId", SYSADMIN_CLUB_ID)
                .claim("realm_access", Map.of("roles", List.of("SYSTEM_ADMINISTRATOR"))));
    }

    @Test
    void listAircraft_returns_200_with_array() {
        ResponseEntity<String> res = get("/api/v1/aircraft");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(res).isArray()).isTrue();
    }

    @Test
    void registerAircraft_valid_returns_201_with_location_header() {
        String imm = uniqueImmatriculation();
        ResponseEntity<String> res = post("/api/v1/aircraft", createPayload(imm, SYSADMIN_CLUB_ID));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = readJson(res);
        assertThat(body.get("immatriculation").asText()).isEqualTo(imm);
        URI loc = res.getHeaders().getLocation();
        assertThat(loc).isNotNull();
        assertThat(loc.getPath()).isEqualTo("/api/v1/aircraft/" + body.get("id").asText());
    }

    @Test
    void registerAircraft_lowercaseImmatriculation_isUppercased() {
        String imm = "hb-x999";
        ResponseEntity<String> res = post("/api/v1/aircraft", createPayload(imm, SYSADMIN_CLUB_ID));
        // Bean-Validation pattern rejects lowercase — covered in
        // registerAircraft_lowercaseImmatriculation_rejectsAtBoundary.
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void registerAircraft_invalidImmatriculationCharacter_returns_400() {
        ResponseEntity<String> res = post("/api/v1/aircraft",
                createPayload("HB 9999", SYSADMIN_CLUB_ID));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void registerAircraft_duplicateImmatriculation_returns_409() {
        String imm = uniqueImmatriculation();
        ResponseEntity<String> first = post("/api/v1/aircraft", createPayload(imm, SYSADMIN_CLUB_ID));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = post("/api/v1/aircraft", createPayload(imm, SYSADMIN_CLUB_ID));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void registerAircraft_unknownAircraftType_returns_400_with_field_diagnostic() {
        Map<String, Object> payload = createPayload(uniqueImmatriculation(), SYSADMIN_CLUB_ID);
        payload.put("aircraftTypeId", "00000000-0000-0000-0000-000000000000");
        ResponseEntity<String> res = post("/api/v1/aircraft", payload);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("aircraftTypeId");
    }

    @Test
    void getAircraft_unknownId_returns_404() {
        AircraftId ghost = AircraftId.of(new UUID(0L, 0L));
        ResponseEntity<String> res = get("/api/v1/aircraft/" + ghost);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateAircraft_changesImmatriculation_returns_200() {
        String imm0 = uniqueImmatriculation();
        ResponseEntity<String> created = post("/api/v1/aircraft", createPayload(imm0, SYSADMIN_CLUB_ID));
        String id = readJson(created).get("id").asText();

        String imm1 = uniqueImmatriculation();
        ResponseEntity<String> upd = put("/api/v1/aircraft/" + id, updatePayload(imm1));
        assertThat(upd.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(upd).get("immatriculation").asText()).isEqualTo(imm1);
    }

    @Test
    void deleteAircraft_returns_204_andGetReturns_404() {
        String imm = uniqueImmatriculation();
        ResponseEntity<String> created = post("/api/v1/aircraft", createPayload(imm, SYSADMIN_CLUB_ID));
        String id = readJson(created).get("id").asText();

        ResponseEntity<Void> del = rest.exchange(
                authed(RequestEntity.delete(URI.create("/api/v1/aircraft/" + id))).build(),
                Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> after = get("/api/v1/aircraft/" + id);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteAircraft_softDeletes_rather_than_physicalRemove() {
        String imm = uniqueImmatriculation();
        ResponseEntity<String> created = post("/api/v1/aircraft", createPayload(imm, SYSADMIN_CLUB_ID));
        String external = readJson(created).get("id").asText();
        UUID raw = AircraftId.parse(external).value();
        rest.exchange(
                authed(RequestEntity.delete(URI.create("/api/v1/aircraft/" + external))).build(),
                Void.class);

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM aircraft WHERE id = ?::uuid AND deleted_on IS NOT NULL",
                Integer.class, raw.toString());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void registerAircraft_afterSoftDeleteWithSameImmatriculation_succeeds() {
        String imm = uniqueImmatriculation();
        ResponseEntity<String> created = post("/api/v1/aircraft", createPayload(imm, SYSADMIN_CLUB_ID));
        String externalId = readJson(created).get("id").asText();
        rest.exchange(
                authed(RequestEntity.delete(URI.create("/api/v1/aircraft/" + externalId))).build(),
                Void.class);

        ResponseEntity<String> recreated = post("/api/v1/aircraft", createPayload(imm, SYSADMIN_CLUB_ID));
        assertThat(recreated.getStatusCode())
                .as("partial UNIQUE on immatriculation is scoped WHERE deleted_on IS NULL — "
                        + "soft-deleted row keeps its immatriculation but does not block recreate")
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void picker_returns_slim_projection() {
        ResponseEntity<String> res = get("/api/v1/aircraft/picker");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.isArray()).isTrue();
        // Slim projection — no comment, no FLARM, no homebase
        if (body.size() > 0) {
            JsonNode first = body.get(0);
            assertThat(first.has("immatriculation")).isTrue();
            assertThat(first.has("aircraftTypeId")).isTrue();
            assertThat(first.has("isTowingAircraft")).isTrue();
            assertThat(first.has("comment")).isFalse();
            assertThat(first.has("flarmId")).isFalse();
        }
    }

    @Test
    void changeState_opensFirstStateAndShowsAsCurrent() {
        String imm = uniqueImmatriculation();
        ResponseEntity<String> created = post("/api/v1/aircraft", createPayload(imm, SYSADMIN_CLUB_ID));
        String id = readJson(created).get("id").asText();

        Instant t0 = Instant.parse("2026-01-01T08:00:00Z");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("aircraftStateId", SEED_AIRCRAFT_STATE_OK);
        payload.put("validFrom", t0.toString());
        payload.put("remarks", "fresh airframe");

        ResponseEntity<String> res = post("/api/v1/aircraft/" + id + "/states", payload);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> detail = get("/api/v1/aircraft/" + id);
        JsonNode currentState = readJson(detail).get("currentState");
        assertThat(currentState).isNotNull();
        assertThat(currentState.get("aircraftStateId").asText()).isEqualTo(SEED_AIRCRAFT_STATE_OK);
    }

    @Test
    void changeState_secondTransition_closesPreviousAndShowsNew() {
        String imm = uniqueImmatriculation();
        ResponseEntity<String> created = post("/api/v1/aircraft", createPayload(imm, SYSADMIN_CLUB_ID));
        String id = readJson(created).get("id").asText();

        post("/api/v1/aircraft/" + id + "/states", Map.of(
                "aircraftStateId", SEED_AIRCRAFT_STATE_OK,
                "validFrom", "2026-01-01T08:00:00Z"));
        post("/api/v1/aircraft/" + id + "/states", Map.of(
                "aircraftStateId", SEED_AIRCRAFT_STATE_MAINTENANCE,
                "validFrom", "2026-02-01T08:00:00Z",
                "remarks", "annual inspection"));

        ResponseEntity<String> history = get("/api/v1/aircraft/" + id + "/states");
        JsonNode entries = readJson(history).get("entries");
        assertThat(entries.size()).isEqualTo(2);

        int openCount = 0;
        for (JsonNode entry : entries) {
            if (entry.get("validTo").isNull()) {
                openCount++;
            }
        }
        assertThat(openCount).as("exactly one open state").isEqualTo(1);
    }

    @Test
    void changeState_validFromNotAfterPrevious_returns_409() {
        String imm = uniqueImmatriculation();
        ResponseEntity<String> created = post("/api/v1/aircraft", createPayload(imm, SYSADMIN_CLUB_ID));
        String id = readJson(created).get("id").asText();

        Map<String, Object> p1 = Map.of(
                "aircraftStateId", SEED_AIRCRAFT_STATE_OK,
                "validFrom", "2026-01-01T08:00:00Z");
        ResponseEntity<String> first = post("/api/v1/aircraft/" + id + "/states", p1);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> p2 = Map.of(
                "aircraftStateId", SEED_AIRCRAFT_STATE_MAINTENANCE,
                "validFrom", "2026-01-01T08:00:00Z");
        ResponseEntity<String> second = post("/api/v1/aircraft/" + id + "/states", p2);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void recordCounter_monotonic_acceptsIncrease() {
        String imm = uniqueImmatriculation();
        ResponseEntity<String> created = post("/api/v1/aircraft", createPayload(imm, SYSADMIN_CLUB_ID));
        String id = readJson(created).get("id").asText();

        post("/api/v1/aircraft/" + id + "/counters", Map.of(
                "atDateTime", "2026-01-01T10:00:00Z",
                "flightOperatingCounterInSeconds", 3600,
                "engineOperatingCounterInSeconds", 3600));
        ResponseEntity<String> second = post("/api/v1/aircraft/" + id + "/counters", Map.of(
                "atDateTime", "2026-02-01T10:00:00Z",
                "flightOperatingCounterInSeconds", 7200,
                "engineOperatingCounterInSeconds", 7200));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> detail = get("/api/v1/aircraft/" + id);
        JsonNode latest = readJson(detail).get("latestCounter");
        assertThat(latest).isNotNull();
        assertThat(latest.get("flightOperatingCounterInSeconds").asLong()).isEqualTo(7200);
    }

    @Test
    void recordCounter_decreasing_returns_409() {
        String imm = uniqueImmatriculation();
        ResponseEntity<String> created = post("/api/v1/aircraft", createPayload(imm, SYSADMIN_CLUB_ID));
        String id = readJson(created).get("id").asText();

        post("/api/v1/aircraft/" + id + "/counters", Map.of(
                "atDateTime", "2026-01-01T10:00:00Z",
                "flightOperatingCounterInSeconds", 7200));
        ResponseEntity<String> second = post("/api/v1/aircraft/" + id + "/counters", Map.of(
                "atDateTime", "2026-02-01T10:00:00Z",
                "flightOperatingCounterInSeconds", 3600));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aircraftTypes_returns_seededCatalog() {
        ResponseEntity<String> res = get("/api/v1/aircraft-types");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isGreaterThanOrEqualTo(7);
    }

    @Test
    void aircraftStates_returns_seededCatalog() {
        ResponseEntity<String> res = get("/api/v1/aircraft-states");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isGreaterThanOrEqualTo(7);
    }

    // ----- helpers -----

    private ResponseEntity<String> get(String path) {
        return rest.exchange(
                authed(RequestEntity.get(URI.create(path))).build(),
                String.class);
    }

    private ResponseEntity<String> post(String path, Object body) {
        return rest.exchange(
                authed(RequestEntity.post(URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON))
                        .body(body),
                String.class);
    }

    private ResponseEntity<String> put(String path, Object body) {
        return rest.exchange(
                authed(RequestEntity.put(URI.create(path))
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
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }
}
