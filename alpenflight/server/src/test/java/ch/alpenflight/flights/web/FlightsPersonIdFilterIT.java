package ch.alpenflight.flights.web;

import static ch.alpenflight.flights.web.FlightsTestFixtures.SEED_FLIGHT_CREW_TYPE_PIC;
import static ch.alpenflight.flights.domain.FlightCrewTypeIds.PASSENGER;
import static ch.alpenflight.flights.domain.FlightProcessState.DELIVERY_BOOKED;
import static ch.alpenflight.flights.web.FlightsTestFixtures.cleanFlightRowsFor;
import static ch.alpenflight.flights.web.FlightsTestFixtures.createPayload;
import static ch.alpenflight.flights.web.FlightsTestFixtures.crewItem;
import static ch.alpenflight.flights.web.FlightsTestFixtures.seedAircraftFor;
import static ch.alpenflight.flights.web.FlightsTestFixtures.seedPersonInClub;
import static ch.alpenflight.flights.web.FlightsTestFixtures.singletonCrew;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.id.PersonId;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
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
 * {@code GET /api/v1/flights?personId={uuid}} — S-165 dashboard contract.
 * Filters list rows to flights where a non-deleted {@code flight_crew} row
 * carries the supplied {@code person_id}. {@code @TenantId} on Flight stays
 * authoritative — cross-tenant person ids match nothing rather than 403.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class FlightsPersonIdFilterIT extends PostgresIntegrationTest {

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
        jdbc.update("DELETE FROM t_aircraft WHERE managing_club_id = ?::uuid AND "
                + "immatriculation LIKE 'HB-FT%'", CLUB_ID);
        UUID aid = seedAircraftFor(jdbc, CLUB_UUID);
        aircraftIdExternal = "ac-" + aid;
    }

    @Test
    void list_withoutPersonIdFilter_returnsAllFlightsInWindow() {
        UUID pilot = seedPersonInClub(jdbc, CLUB_UUID);
        String pilotExt = PersonId.of(pilot).toExternal();
        String pilotFlightId = createFlightWithPic(pilotExt, "2026-05-01");
        String otherFlightId = createFlightNoCrew("2026-05-02");

        JsonNode items = readJson(get(
                "/api/v1/flights?from=2026-05-01&to=2026-05-31&limit=50")).get("items");
        assertThat(extractIds(items))
                .as("Without personId filter, both flights appear")
                .contains(pilotFlightId, otherFlightId);
    }

    @Test
    void list_withPersonIdFilter_returnsOnlyFlightsForThatPerson() {
        UUID pilotA = seedPersonInClub(jdbc, CLUB_UUID);
        UUID pilotB = seedPersonInClub(jdbc, CLUB_UUID);
        String pilotAExt = PersonId.of(pilotA).toExternal();
        String pilotBExt = PersonId.of(pilotB).toExternal();
        String aFlight = createFlightWithPic(pilotAExt, "2026-05-01");
        String bFlight = createFlightWithPic(pilotBExt, "2026-05-02");

        JsonNode items = readJson(get(
                "/api/v1/flights?personId=" + pilotAExt + "&from=2026-05-01&to=2026-05-31"
                        + "&limit=50")).get("items");

        assertThat(extractIds(items))
                .as("personId filter limits result to flights crewed by that person")
                .contains(aFlight)
                .doesNotContain(bFlight);
    }

    @Test
    void list_withPersonIdFilter_includesFlightsInAnyNonDeletedCrewRole() {
        UUID pilot = seedPersonInClub(jdbc, CLUB_UUID);
        String pilotExt = PersonId.of(pilot).toExternal();
        // The AC says ANY non-deleted crew role qualifies. Seed two flights
        // with the same person in different roles (PIC + PASSENGER); both
        // must surface — proves the SQL EXISTS clause carries no
        // `flightCrewTypeId` predicate.
        String picFlight = createFlightWithCrew(pilotExt, SEED_FLIGHT_CREW_TYPE_PIC, "2026-05-01");
        String paxFlight = createFlightWithCrew(pilotExt, PASSENGER.toString(), "2026-05-02");

        JsonNode items = readJson(get(
                "/api/v1/flights?personId=" + PersonId.of(pilot).toExternal() + "&from=2026-05-01&to=2026-05-31"
                        + "&limit=50")).get("items");

        assertThat(extractIds(items)).contains(picFlight, paxFlight);
    }

    @Test
    void list_withPersonIdFilter_includesFlightsInTerminalProcessStates() {
        // AC: "Includes flights in any process state (NotProcessed / Valid /
        // Invalid / Locked / DeliveryBooked / ExcludedFromDeliveryProcess) —
        // only `deleted_on IS NULL` filtered out." Force the seeded flight
        // into a terminal state via JDBC (public create only stamps
        // NOT_PROCESSED) and confirm it still surfaces under the filter.
        UUID pilot = seedPersonInClub(jdbc, CLUB_UUID);
        String pilotExt = PersonId.of(pilot).toExternal();
        String flightId = createFlightWithCrew(pilotExt, SEED_FLIGHT_CREW_TYPE_PIC, "2026-05-01");
        UUID flightUuid = UUID.fromString(flightId.substring(3));
        jdbc.update("UPDATE t_flight SET process_state_id = ?::uuid WHERE id = ?::uuid",
                DELIVERY_BOOKED.id().toString(), flightUuid.toString());

        JsonNode items = readJson(get(
                "/api/v1/flights?personId=" + PersonId.of(pilot).toExternal()
                        + "&from=2026-05-01&to=2026-05-31&limit=50")).get("items");
        assertThat(extractIds(items))
                .as("Process-state gates do not narrow the personId filter result set")
                .contains(flightId);
    }

    @Test
    void list_withPersonIdFilter_excludesFlightsWhereCrewRowIsSoftDeleted() {
        UUID pilot = seedPersonInClub(jdbc, CLUB_UUID);
        String pilotExt = PersonId.of(pilot).toExternal();
        String flightId = createFlightWithPic(pilotExt, "2026-05-01");
        UUID flightUuid = UUID.fromString(flightId.substring(3));
        jdbc.update("UPDATE t_flight_crew SET deleted_on = now() "
                + "WHERE flight_id = ?::uuid AND person_id = ?::uuid",
                flightUuid.toString(), pilot.toString());

        JsonNode items = readJson(get(
                "/api/v1/flights?personId=" + PersonId.of(pilot).toExternal() + "&from=2026-05-01&to=2026-05-31"
                        + "&limit=50")).get("items");
        assertThat(extractIds(items))
                .as("Soft-deleted crew rows are filtered out — the user is no longer "
                        + "on that flight's crew")
                .doesNotContain(flightId);
    }

    @Test
    void list_withPersonIdFilter_respectsTenantScopeOnFlight() {
        // A person id from another tenant matches nothing. No 403 — the IDOR
        // contract is "404 / empty", not "leaks existence via status code".
        UUID foreignClub = UUID.fromString("019e30c3-2c00-7001-8000-0000000000c1");
        UUID countryId = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        UUID clubStateId = jdbc.queryForObject("SELECT id FROM t_club_state LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO t_club (id, clubname, club_key, country_id, club_state_id,
                                  slug, public_registration_enabled)
                VALUES (?::uuid, ?, ?, ?::uuid, ?::uuid, ?, false)
                ON CONFLICT (id) DO NOTHING
                """,
                foreignClub.toString(), "IT_FPF_x", "IT_FPF_x",
                countryId.toString(), clubStateId.toString(), "IT_FPF_x");
        UUID foreignPerson = seedPersonInClub(jdbc, foreignClub);

        ResponseEntity<String> res = get(
                "/api/v1/flights?personId=" + PersonId.of(foreignPerson).toExternal()
                        + "&from=2026-05-01&to=2026-05-31&limit=50");
        assertThat(res.getStatusCode())
                .as("Cross-tenant personId is an empty match, never a 403")
                .isEqualTo(HttpStatus.OK);
        assertThat(readJson(res).get("items")).isEmpty();
    }

    @Test
    void list_withMalformedPersonId_returns400() {
        ResponseEntity<String> res = get(
                "/api/v1/flights?personId=not-a-uuid&from=2026-05-01&to=2026-05-31&limit=50");
        assertThat(res.getStatusCode())
                .as("Malformed personId is a validation failure, surfaced as 400 ProblemDetail")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void list_withPersonIdFilter_limitOne_returnsMostRecentByFlightDateThenStartThenCreated() {
        // AC sort: flight_date DESC, start_date_time DESC NULLS LAST, created_on DESC.
        // Two flights share the latest flight_date — the one with the LATER
        // start_date_time wins. A third flight on an earlier date is dropped
        // by the limit=1.
        UUID pilot = seedPersonInClub(jdbc, CLUB_UUID);
        String pilotExt = PersonId.of(pilot).toExternal();
        createFlightWithPicAndStart(pilotExt, "2026-05-09", "2026-05-09T07:00:00Z");
        String winner = createFlightWithPicAndStart(pilotExt, "2026-05-10", "2026-05-10T08:00:00Z");
        createFlightWithPicAndStart(pilotExt, "2026-05-10", "2026-05-10T06:00:00Z");

        JsonNode items = readJson(get(
                "/api/v1/flights?personId=" + PersonId.of(pilot).toExternal() + "&from=2026-05-01&to=2026-05-31"
                        + "&limit=1")).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("id").asText())
                .as("limit=1 returns the most recent flight per the AC sort")
                .isEqualTo(winner);
    }

    private String createFlightWithPic(String personIdExternal, String flightDateIso) {
        return createFlightWithCrew(personIdExternal, SEED_FLIGHT_CREW_TYPE_PIC, flightDateIso);
    }

    private String createFlightWithCrew(String personIdExternal,
                                        String flightCrewTypeId,
                                        String flightDateIso) {
        Map<String, Object> payload = createPayload("GLIDER", aircraftIdExternal, flightDateIso);
        payload.put("crew",
                singletonCrew(crewItem(personIdExternal, flightCrewTypeId)));
        ResponseEntity<String> res = post("/api/v1/flights", payload);
        return readJson(res).get("id").asText();
    }

    private String createFlightWithPicAndStart(String personIdExternal,
                                               String flightDateIso,
                                               String startDateTimeIso) {
        Map<String, Object> payload = createPayload("GLIDER", aircraftIdExternal, flightDateIso);
        payload.put("startDateTime", startDateTimeIso);
        payload.put("crew",
                singletonCrew(crewItem(personIdExternal, SEED_FLIGHT_CREW_TYPE_PIC)));
        ResponseEntity<String> res = post("/api/v1/flights", payload);
        return readJson(res).get("id").asText();
    }

    private String createFlightNoCrew(String flightDateIso) {
        Map<String, Object> payload = new LinkedHashMap<>(
                createPayload("GLIDER", aircraftIdExternal, flightDateIso));
        ResponseEntity<String> res = post("/api/v1/flights", payload);
        return readJson(res).get("id").asText();
    }

    private static List<String> extractIds(JsonNode items) {
        List<String> out = new ArrayList<>();
        items.forEach(n -> out.add(n.get("id").asText()));
        return out;
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
