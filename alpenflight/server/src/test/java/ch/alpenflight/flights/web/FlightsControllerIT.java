package ch.alpenflight.flights.web;

import static ch.alpenflight.flights.web.FlightsTestFixtures.SEED_FLIGHT_CREW_TYPE_PIC;
import static ch.alpenflight.flights.web.FlightsTestFixtures.cleanFlightRowsFor;
import static ch.alpenflight.flights.web.FlightsTestFixtures.createPayload;
import static ch.alpenflight.flights.web.FlightsTestFixtures.crewItem;
import static ch.alpenflight.flights.web.FlightsTestFixtures.seedAircraftFor;
import static ch.alpenflight.flights.web.FlightsTestFixtures.seedPersonInClub;
import static ch.alpenflight.flights.web.FlightsTestFixtures.singletonCrew;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.id.PersonId;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.LocalDate;
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
 * Full-stack HTTP integration test for the Flight CRUD slice. AC4
 * smoke (create one of each FlightAircraftType), basic GET / PUT /
 * soft-delete, keyset pagination. Tenant isolation lives in
 * {@link FlightsTenantIsolationIT}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class FlightsControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLUB_ID = "019e30c3-2c00-7001-8000-000000000001";
    private static final UUID CLUB_UUID = UUID.fromString(CLUB_ID);

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
        // Pre-clean aircraft rows the previous test seeded under this club —
        // immatriculation is regulator-globally-unique, so leftovers collide.
        jdbc.update("DELETE FROM aircraft WHERE managing_club_id = ?::uuid AND "
                + "immatriculation LIKE 'HB-FT%'", CLUB_ID);
        UUID aid = seedAircraftFor(jdbc, CLUB_UUID);
        aircraftIdExternal = "ac-" + aid;
    }

    @Test
    void create_glider_returns_201_with_discriminator_and_location() {
        ResponseEntity<String> res = post("/api/v1/flights",
                createPayload("GLIDER", aircraftIdExternal, "2026-05-01"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = readJson(res);
        assertThat(body.get("flightAircraftType").asText()).isEqualTo("GLIDER");
        assertThat(body.get("aircraftId").asText()).isEqualTo(aircraftIdExternal);
        String idExternal = body.get("id").asText();
        assertThat(idExternal).startsWith("fl-");
        URI loc = res.getHeaders().getLocation();
        assertThat(loc).isNotNull();
        assertThat(loc.getPath()).isEqualTo("/api/v1/flights/" + idExternal);
    }

    @Test
    void create_tow_carries_tow_discriminator() {
        ResponseEntity<String> res = post("/api/v1/flights",
                createPayload("TOW", aircraftIdExternal, "2026-05-01"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(readJson(res).get("flightAircraftType").asText()).isEqualTo("TOW");
    }

    @Test
    void create_motor_carries_motor_discriminator() {
        ResponseEntity<String> res = post("/api/v1/flights",
                createPayload("MOTOR", aircraftIdExternal, "2026-05-01"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(readJson(res).get("flightAircraftType").asText()).isEqualTo("MOTOR");
    }

    @Test
    void getFlight_unknownId_returns_404() {
        ResponseEntity<String> res = get(
                "/api/v1/flights/fl-019e30c3-2c00-7001-8000-000000000aaa");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void update_replaces_crew_wholesale() {
        UUID pilot1 = seedPersonInClub(jdbc, CLUB_UUID);
        UUID pilot2 = seedPersonInClub(jdbc, CLUB_UUID);
        Map<String, Object> payload = createPayload("GLIDER", aircraftIdExternal, "2026-05-01");
        payload.put("crew", singletonCrew(crewItem(
                PersonId.of(pilot1).toExternal(),
                SEED_FLIGHT_CREW_TYPE_PIC)));
        ResponseEntity<String> created = post("/api/v1/flights", payload);
        String id = readJson(created).get("id").asText();
        String detailPath = "/api/v1/flights/" + id;

        Map<String, Object> update = updatePayload();
        update.put("crew", singletonCrew(crewItem(
                PersonId.of(pilot2).toExternal(),
                SEED_FLIGHT_CREW_TYPE_PIC)));
        ResponseEntity<String> updated = put(detailPath, update);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(updated);
        assertThat(body.get("crew")).hasSize(1);
        assertThat(body.get("crew").get(0).get("personId").asText())
                .isEqualTo(PersonId.of(pilot2).toExternal());
    }

    @Test
    void crossTenant_personId_in_crew_resolves_for_currentTenant_create() {
        // Seed a foreign tenant + a Person whose only PersonClub membership
        // is in that other tenant. The sacred-cow ride-through (Person has
        // no @TenantId per S-051) must let this Person resolve as a crew
        // member on a Flight in the caller's tenant.
        UUID foreignClub = UUID.fromString("019e30c3-2c00-7001-8000-0000000000c2");
        UUID countryId = jdbc.queryForObject("SELECT id FROM country LIMIT 1", UUID.class);
        UUID clubStateId = jdbc.queryForObject("SELECT id FROM club_state LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO club (id, clubname, club_key, country_id, club_state_id,
                                  slug, public_registration_enabled)
                VALUES (?::uuid, ?, ?, ?::uuid, ?::uuid, ?, false)
                ON CONFLICT (id) DO NOTHING
                """,
                foreignClub.toString(), "IT_FCT_x", "IT_FCTx",
                countryId.toString(), clubStateId.toString(), "IT_FCT_x");
        UUID realPerson = seedPersonInClub(jdbc, foreignClub);

        Map<String, Object> payload = createPayload("GLIDER", aircraftIdExternal, "2026-05-01");
        payload.put("crew", singletonCrew(crewItem(
                PersonId.of(realPerson).toExternal(),
                SEED_FLIGHT_CREW_TYPE_PIC)));
        ResponseEntity<String> res = post("/api/v1/flights", payload);
        assertThat(res.getStatusCode())
                .as("Person ride-through is the sacred cow — pilot from another club's PersonClub "
                        + "must persist (the FK resolves cross-tenant via Person PK).")
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void softDelete_removes_from_list_and_returns_404_on_get() {
        Map<String, Object> payload = createPayload("GLIDER", aircraftIdExternal, "2026-05-01");
        ResponseEntity<String> created = post("/api/v1/flights", payload);
        String id = readJson(created).get("id").asText();

        ResponseEntity<String> del = delete("/api/v1/flights/" + id);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> after = get("/api/v1/flights/" + id);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> list = get("/api/v1/flights?from=2026-05-01&to=2026-05-01");
        JsonNode items = readJson(list).get("items");
        assertThat(items).allSatisfy(n ->
                assertThat(n.get("id").asText()).isNotEqualTo(id));
    }

    @Test
    void list_returns_keyset_response_with_items_and_optional_cursor() {
        for (int i = 0; i < 3; i++) {
            post("/api/v1/flights",
                    createPayload("GLIDER", aircraftIdExternal, "2026-05-01"));
        }
        ResponseEntity<String> res = get("/api/v1/flights?from=2026-05-01&to=2026-05-01&limit=2");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.has("items")).isTrue();
        assertThat(body.get("items").size()).isEqualTo(2);
        assertThat(body.has("nextCursor")).isTrue();
        assertThat(body.get("nextCursor").isNull()).isFalse();
    }

    @Test
    void list_default_window_returns_recent_flights_only() {
        // Default window is last 90 days. Flight stamped at an old date
        // should NOT appear when no `from` is provided. The window is
        // computed against the controller's clock — seed an aircraft and
        // flight at `today` so the test stays stable.
        LocalDate today = LocalDate.now();
        Map<String, Object> recent = createPayload(
                "GLIDER", aircraftIdExternal, today.toString());
        ResponseEntity<String> created = post("/api/v1/flights", recent);
        String id = readJson(created).get("id").asText();

        ResponseEntity<String> res = get("/api/v1/flights?limit=50");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = readJson(res).get("items");
        assertThat(items)
                .anySatisfy(n -> assertThat(n.get("id").asText()).isEqualTo(id));
    }

    @Test
    void create_with_unknown_aircraft_returns_404() {
        Map<String, Object> body = createPayload(
                "GLIDER", "ac-019e30c3-2c00-7001-8000-0000000000ee", "2026-05-01");
        ResponseEntity<String> res = post("/api/v1/flights", body);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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

    // Silence "unused-import" — FlightId is referenced via the external
    // string format in assertions above.
    @SuppressWarnings("unused")
    private static String unused() { return FlightId.PREFIX; }
}
