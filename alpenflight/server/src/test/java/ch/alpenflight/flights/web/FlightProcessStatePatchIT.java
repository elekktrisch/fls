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
    private static final String CLUB_ID = "019e30c3-2c00-7001-8000-000000000001";
    private static final UUID CLUB_UUID = UUID.fromString(CLUB_ID);

    private static final UUID NOT_PROCESSED = UUID.fromString("019e2e15-2c00-7a98-8000-000000003a98");
    private static final UUID VALID = UUID.fromString("019e2e15-2c00-7a9a-8000-000000003a9a");
    private static final UUID LOCKED = UUID.fromString("019e2e15-2c00-7a9b-8000-000000003a9b");
    private static final UUID DELIVERY_BOOKED = UUID.fromString("019e2e15-2c00-7a9e-8000-000000003a9e");
    private static final UUID EXCLUDED = UUID.fromString("019e2e15-2c00-7a9f-8000-000000003a9f");

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
        cleanFlightRowsFor(jdbc, CLUB_UUID);
        jdbc.update("DELETE FROM aircraft WHERE managing_club_id = ?::uuid "
                + "AND immatriculation LIKE 'HB-FT%'", CLUB_ID);
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
        // Seed a flight under a different club and try to PATCH it with our token.
        UUID otherClub = UUID.fromString("019e30c3-2c00-7001-8000-000000000099");
        jdbc.update("""
                INSERT INTO club (id, name, business_name, country_id, language_id,
                                  homebase_id)
                VALUES (?::uuid, 'Other', 'Other AG',
                        (SELECT id FROM country WHERE code='CH'),
                        (SELECT id FROM language WHERE code='DE'),
                        NULL)
                ON CONFLICT (id) DO NOTHING
                """, otherClub.toString());
        UUID otherAircraft = seedAircraftFor(jdbc, otherClub);
        UUID otherFlightId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO flight (id, operating_club_id, aircraft_id,
                                    flight_aircraft_type_id, flight_date,
                                    is_solo_flight, no_start_time_information,
                                    no_ldg_time_information,
                                    air_state_id, process_state_id, version)
                VALUES (?::uuid, ?::uuid, ?::uuid, 1, '2026-05-01',
                        false, false, false,
                        '019e2e15-2c00-7e80-8000-000000003e80'::uuid,
                        ?::uuid, 0)
                """,
                otherFlightId.toString(),
                otherClub.toString(),
                otherAircraft.toString(),
                VALID.toString());

        ResponseEntity<String> res = patch(
                "/api/v1/flights/fl-" + otherFlightId + "/process-state",
                Map.of("processState", "LOCKED"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Cleanup.
        jdbc.update("DELETE FROM flight WHERE operating_club_id = ?::uuid", otherClub.toString());
        jdbc.update("DELETE FROM aircraft WHERE managing_club_id = ?::uuid", otherClub.toString());
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
            jdbc.update("UPDATE flight SET process_state_id = ?::uuid WHERE id = ?::uuid",
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
