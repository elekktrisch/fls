package ch.alpenflight.flights.web;

import static ch.alpenflight.flights.web.FlightsTestFixtures.cleanFlightRowsFor;
import static ch.alpenflight.flights.web.FlightsTestFixtures.seedAircraftFor;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
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
 * Cross-tenant properties for the Flight aggregate. Counterparts to the
 * sibling per-aggregate isolation ITs.
 *
 * <ul>
 *   <li>{@code @TenantId} on {@code operating_club_id} hides another
 *       tenant's flight on {@code GET /flights/{id}} — 404, not 403.</li>
 *   <li>Cross-tenant Aircraft references are intentionally allowed —
 *       Club B's Flight may reference Club A's tow plane (S-058 reversion
 *       of S-159). The list-isolation assertion below pins both shapes
 *       in one test.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class FlightsTenantIsolationIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NAME_PREFIX = "IT_FTI_";
    private static final String KEY_PREFIX = "IT_FT";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID clubA;
    private UUID clubB;

    @BeforeEach
    void seedTwoClubs() {
        // Aircraft is cross-tenant since S-058 (reverts S-159); TwoClubFixture
        // wipes aircraft rows under the seed clubs explicitly before delete-clubs.
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, NAME_PREFIX, KEY_PREFIX);
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
        cleanFlightRowsFor(jdbc, clubA, clubB);
    }

    @Test
    void getFlight_acrossTenant_returns_404() {
        UUID aircraftA = seedAircraftFor(jdbc, clubA);
        String clubAToken = mintToken(clubA);
        Map<String, Object> body = body("GLIDER", "ac-" + aircraftA);
        ResponseEntity<String> created = post("/api/v1/flights", body, clubAToken);
        String flightId = readJson(created).get("id").asText();

        // Caller in Club B: 404 (the row is invisible under B's @TenantId scope).
        String clubBToken = mintToken(clubB);
        ResponseEntity<String> res = get("/api/v1/flights/" + flightId, clubBToken);
        assertThat(res.getStatusCode())
                .as("Cross-tenant access surfaces as 404, NOT 403 (IDOR contract)")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateFlight_acrossTenant_returns_404() {
        // Oracle #24: legacy FlightService update loads by id with NO ClubId
        // filter, so a foreign caller could edit another club's flight by
        // guessing the id. The new stack scopes structurally via @TenantId —
        // Club B's PUT against Club A's flight id finds no row → 404 (the
        // write never reaches the aggregate), NOT a silent cross-tenant edit.
        UUID aircraftA = seedAircraftFor(jdbc, clubA);
        String clubAToken = mintToken(clubA);
        ResponseEntity<String> created = post("/api/v1/flights",
                body("GLIDER", "ac-" + aircraftA), clubAToken);
        String flightId = readJson(created).get("id").asText();

        // Club B targets A's flight id with B's own (valid) aircraft in the
        // body so the 404 can only come from the tenant-scoped row lookup,
        // not from a body-validation or FK failure.
        UUID aircraftB = seedAircraftFor(jdbc, clubB);
        String clubBToken = mintToken(clubB);
        ResponseEntity<String> res = put("/api/v1/flights/" + flightId,
                updateBody("ac-" + aircraftB), clubBToken);
        assertThat(res.getStatusCode())
                .as("Cross-tenant PUT surfaces as 404, NOT 403 or a silent edit (oracle #24)")
                .isEqualTo(HttpStatus.NOT_FOUND);

        // The owning club's flight is untouched (no cross-tenant write landed).
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight WHERE id = ?::uuid AND operating_club_id = ?::uuid",
                Integer.class, rawId(flightId), clubA.toString());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void deleteFlight_acrossTenant_returns_404() {
        // Oracle #24: legacy delete loads by id with NO ClubId filter. The new
        // stack scopes structurally — Club B's DELETE against Club A's flight
        // id finds no row → 404, and A's row stays alive (not soft-deleted).
        UUID aircraftA = seedAircraftFor(jdbc, clubA);
        String clubAToken = mintToken(clubA);
        ResponseEntity<String> created = post("/api/v1/flights",
                body("GLIDER", "ac-" + aircraftA), clubAToken);
        String flightId = readJson(created).get("id").asText();

        String clubBToken = mintToken(clubB);
        ResponseEntity<String> res = delete("/api/v1/flights/" + flightId, clubBToken);
        assertThat(res.getStatusCode())
                .as("Cross-tenant DELETE surfaces as 404, NOT 403 or a silent delete (oracle #24)")
                .isEqualTo(HttpStatus.NOT_FOUND);

        // A's flight is still present and NOT soft-deleted.
        Integer liveCount = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight WHERE id = ?::uuid"
                        + " AND operating_club_id = ?::uuid AND deleted_on IS NULL",
                Integer.class, rawId(flightId), clubA.toString());
        assertThat(liveCount)
                .as("the owning club's flight is not soft-deleted by a foreign DELETE")
                .isEqualTo(1);
    }

    @Test
    void createFlight_referencing_other_tenants_aircraft_succeeds() {
        // S-058 (reverts S-159): the charter case is first-class. Club B
        // creating a Flight against Club A's aircraft must persist —
        // Aircraft is cross-tenant; only the Flight's operating_club_id
        // carries the @TenantId discriminator.
        UUID aircraftA = seedAircraftFor(jdbc, clubA);
        String clubBToken = mintToken(clubB);
        Map<String, Object> body = body("GLIDER", "ac-" + aircraftA);
        ResponseEntity<String> res = post("/api/v1/flights", body, clubBToken);
        assertThat(res.getStatusCode())
                .as("Charter case: Club B's Flight may reference Club A's aircraft.")
                .isEqualTo(HttpStatus.CREATED);

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight WHERE operating_club_id = ?::uuid",
                Integer.class, clubB.toString());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void list_isolates_flights_per_tenant() {
        UUID aircraftA = seedAircraftFor(jdbc, clubA);
        UUID aircraftB = seedAircraftFor(jdbc, clubB);
        String clubAToken = mintToken(clubA);
        String clubBToken = mintToken(clubB);
        ResponseEntity<String> aCreated =
                post("/api/v1/flights", body("GLIDER", "ac-" + aircraftA), clubAToken);
        ResponseEntity<String> bCreated =
                post("/api/v1/flights", body("GLIDER", "ac-" + aircraftB), clubBToken);
        String idA = readJson(aCreated).get("id").asText();
        String idB = readJson(bCreated).get("id").asText();

        // A's list contains A's row, not B's.
        JsonNode aList = readJson(get(
                "/api/v1/flights?from=2026-05-01&to=2026-05-31&limit=200", clubAToken))
                .get("items");
        assertThat(extractIds(aList)).contains(idA).doesNotContain(idB);

        // B's list contains B's row, not A's.
        JsonNode bList = readJson(get(
                "/api/v1/flights?from=2026-05-01&to=2026-05-31&limit=200", clubBToken))
                .get("items");
        assertThat(extractIds(bList)).contains(idB).doesNotContain(idA);
    }

    private String mintToken(UUID clubId) {
        return jwts.mint(c -> c
                .claim("clubId", clubId.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    private static Map<String, Object> body(String type, String aircraftIdExternal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flightAircraftType", type);
        body.put("aircraftId", aircraftIdExternal);
        body.put("flightDate", "2026-05-15");
        body.put("isSoloFlight", false);
        body.put("noStartTimeInformation", false);
        body.put("noLdgTimeInformation", false);
        body.put("crew", List.of());
        return body;
    }

    private static Map<String, Object> updateBody(String aircraftIdExternal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("aircraftId", aircraftIdExternal);
        body.put("flightDate", "2026-05-15");
        body.put("isSoloFlight", false);
        body.put("noStartTimeInformation", false);
        body.put("noLdgTimeInformation", false);
        body.put("crew", List.of());
        return body;
    }

    /** Strips the {@code fl-} external-id prefix for a raw {@code ::uuid} SQL bind. */
    private static String rawId(String externalFlightId) {
        return externalFlightId.startsWith("fl-")
                ? externalFlightId.substring("fl-".length())
                : externalFlightId;
    }

    private static List<String> extractIds(JsonNode items) {
        List<String> out = new java.util.ArrayList<>();
        items.forEach(n -> out.add(n.get("id").asText()));
        return out;
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
