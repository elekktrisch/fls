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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * HTTP slice for {@code PATCH /api/v1/flights/{id}/process-state}.
 * Verifies the legality matrix surfaces as 409, unknown-state as 400,
 * cross-tenant as 404, happy-path as 200. The OPERATOR trigger is the
 * only one exposed via this endpoint — system triggers are invoked from
 * background jobs (deferred stories).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class FlightProcessStatePatchIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Distinct UUIDs per IT so we don't collide with FlightsControllerIT /
    // FlightsTenantIsolationIT when JUnit runs them in the same JVM.
    private static final UUID CLUB_UUID = UUID.fromString("019e30c3-2c00-7001-8000-0000000000d1");
    private static final UUID OTHER_CLUB_UUID = UUID.fromString("019e30c3-2c00-7001-8000-0000000000d2");
    private static final String CLUB_ID = CLUB_UUID.toString();

    private static final UUID NOT_PROCESSED = UUID.fromString("019e2e15-2c00-7a98-8000-000000003a98");
    private static final UUID VALID = UUID.fromString("019e2e15-2c00-7a9a-8000-000000003a9a");
    private static final UUID DELIVERY_BOOKED = UUID.fromString("019e2e15-2c00-7a9e-8000-000000003a9e");

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    private String adminToken;
    private String aircraftIdExternal;

    @BeforeEach
    void setUp() {
        adminToken = jwts.mint(c -> c
                .claim("clubId", CLUB_ID)
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
        cleanFlightRowsFor(jdbc, CLUB_UUID, OTHER_CLUB_UUID);
        // Seed both clubs via the existing fixture (handles aircraft + child
        // cleanup + the complex club FK chain).
        new TwoClubFixture(jdbc, CLUB_UUID, OTHER_CLUB_UUID,
                "patchit", "PAIT").seed();
        UUID aid = seedAircraftFor(jdbc, CLUB_UUID);
        aircraftIdExternal = "ac-" + aid;
    }

    @Test
    void operator_legal_transition_returns_200_and_new_state() {
        String flightId = createFlightInState(VALID);
        ResponseEntity<String> res = patch(
                "/api/v1/flights/" + flightId + "/process-state",
                Map.of("processState", "EXCLUDED_FROM_DELIVERY_PROCESS"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.get("processState").asText())
                .isEqualTo("EXCLUDED_FROM_DELIVERY_PROCESS");
    }

    @Test
    void operator_illegal_transition_returns_409() {
        String flightId = createFlightInState(VALID);
        ResponseEntity<String> res = patch(
                "/api/v1/flights/" + flightId + "/process-state",
                Map.of("processState", "LOCKED"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = readJson(res);
        assertThat(body.get("type").asText())
                .isEqualTo("urn:alpenflight:problem:flight-illegal-transition");
        assertThat(body.get("from").asText()).isEqualTo("VALID");
        assertThat(body.get("to").asText()).isEqualTo("LOCKED");
    }

    @Test
    void delivery_booked_is_terminal_via_patch() {
        String flightId = createFlightInState(DELIVERY_BOOKED);
        ResponseEntity<String> res = patch(
                "/api/v1/flights/" + flightId + "/process-state",
                Map.of("processState", "LOCKED"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void same_state_returns_409() {
        String flightId = createFlightInState(VALID);
        ResponseEntity<String> res = patch(
                "/api/v1/flights/" + flightId + "/process-state",
                Map.of("processState", "VALID"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void unknown_state_returns_400() {
        String flightId = createFlightInState(VALID);
        ResponseEntity<String> res = patch(
                "/api/v1/flights/" + flightId + "/process-state",
                Map.of("processState", "WHO_KNOWS"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknown_flight_returns_404() {
        ResponseEntity<String> res = patch(
                "/api/v1/flights/fl-019e30c3-2c00-7001-8000-000000000aaa/process-state",
                Map.of("processState", "LOCKED"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void cross_tenant_flight_returns_404() {
        // Seed a flight under OTHER_CLUB_UUID and try to PATCH it with our token.
        UUID otherAircraft = seedAircraftFor(jdbc, OTHER_CLUB_UUID);
        UUID otherFlightId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_flight (id, operating_club_id, aircraft_id,
                                    flight_aircraft_type_id, flight_date,
                                    is_solo_flight, no_start_time_information,
                                    no_ldg_time_information,
                                    process_state_id, version)
                VALUES (?::uuid, ?::uuid, ?::uuid, 1, '2026-05-01',
                        false, false, false,
                        ?::uuid, 0)
                """,
                otherFlightId.toString(),
                OTHER_CLUB_UUID.toString(),
                otherAircraft.toString(),
                VALID.toString());

        ResponseEntity<String> res = patch(
                "/api/v1/flights/fl-" + otherFlightId + "/process-state",
                Map.of("processState", "LOCKED"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Creates a flight via the public POST then forces it into the target
     * state via JDBC (the public surface only stamps NOT_PROCESSED on
     * create — system-driven transitions live in deferred stories).
     */
    private String createFlightInState(UUID processStateId) {
        ResponseEntity<String> postRes = post("/api/v1/flights",
                createPayload("GLIDER", aircraftIdExternal, "2026-05-01"));
        assertThat(postRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String idExternal = readJson(postRes).get("id").asText();
        // Strip "fl-" prefix.
        UUID flightUuid = UUID.fromString(idExternal.substring(3));
        if (!NOT_PROCESSED.equals(processStateId)) {
            jdbc.update("UPDATE t_flight SET process_state_id = ?::uuid WHERE id = ?::uuid",
                    processStateId.toString(), flightUuid.toString());
        }
        return idExternal;
    }

    private ResponseEntity<String> patch(String path, Object body) {
        return rest.exchange(
                RequestEntity.method(HttpMethod.PATCH, URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .body(body),
                String.class);
    }

    private ResponseEntity<String> post(String path, Object body) {
        return rest.exchange(
                RequestEntity.post(URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
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
