package ch.alpenflight.accounting.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
class DeliveryCreationTestsControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/deliverycreationtests";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired FlightRepository flights;
    @Autowired AircraftRepository aircraft;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID clubA;
    private UUID clubB;
    private UUID flightA;
    private String adminA;
    private String adminB;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_DCTC_", "IT_DCTC");
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
        flightA = seedGliderFlight(clubA);
        adminA = adminTokenFor(clubA);
        adminB = adminTokenFor(clubB);
    }

    @Test
    void list_returns_200_with_empty_array_initially() {
        ResponseEntity<String> res = get(BASE, adminA);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isZero();
    }

    @Test
    void create_returns_201_with_location_then_get_round_trips() {
        ResponseEntity<String> created = post(BASE, payload(flightA, "Tiered FlightTime"), adminA);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = readJson(created);
        String id = body.get("id").asText();
        assertThat(body.get("testName").asText()).isEqualTo("Tiered FlightTime");
        assertThat(body.get("flightId").asText()).isEqualTo(FlightId.of(flightA).toExternal());

        URI loc = created.getHeaders().getLocation();
        assertThat(loc).isNotNull();
        assertThat(loc.getPath()).isEqualTo(BASE + "/" + id);

        ResponseEntity<String> got = get(BASE + "/" + id, adminA);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode detail = readJson(got);
        assertThat(detail.get("testName").asText()).isEqualTo("Tiered FlightTime");
        assertThat(detail.get("description").asText()).isEqualTo("daily tuning tool");
        assertThat(detail.get("mustNotCreateDeliveryForFlight").asBoolean()).isTrue();
        assertThat(detail.get("ignoreItemText").asBoolean()).isTrue();
        assertThat(detail.get("expectedDelivery").get("items").size())
                .as("a brand-new harness has captured no expected set yet")
                .isZero();
        JsonNode runOn = detail.path("lastTestRunOn");
        assertThat(runOn.isMissingNode() || runOn.isNull())
                .as("a brand-new harness has never run")
                .isTrue();
    }

    @Test
    void list_includes_created_harness() {
        post(BASE, payload(flightA, "Listed harness"), adminA);
        ResponseEntity<String> res = get(BASE, adminA);
        JsonNode body = readJson(res);
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.get(0).get("testName").asText()).isEqualTo("Listed harness");
        JsonNode lastResult = body.get(0).path("lastTestSuccessful");
        assertThat(lastResult.isMissingNode() || lastResult.isNull()).isTrue();
    }

    @Test
    void update_renames_and_returns_200() {
        ResponseEntity<String> created = post(BASE, payload(flightA, "Before"), adminA);
        String id = readJson(created).get("id").asText();

        ResponseEntity<String> res = put(BASE + "/" + id, payload(flightA, "After"), adminA);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(res).get("testName").asText()).isEqualTo("After");
    }

    @Test
    void delete_returns_204_then_get_is_404() {
        ResponseEntity<String> created = post(BASE, payload(flightA, "Doomed"), adminA);
        String id = readJson(created).get("id").asText();

        ResponseEntity<String> del = delete(BASE + "/" + id, adminA);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> got = get(BASE + "/" + id, adminA);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void get_unknownId_returns_404() {
        ResponseEntity<String> res = get(BASE + "/019e30c3-2c00-7001-8000-000000000aaa", adminA);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void crossTenant_get_returns_404() {
        ResponseEntity<String> created = post(BASE, payload(flightA, "Club A private"), adminA);
        String id = readJson(created).get("id").asText();

        ResponseEntity<String> res = get(BASE + "/" + id, adminB);
        assertThat(res.getStatusCode())
                .as("the @TenantId discriminator makes club A's harness invisible, not forbidden")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void create_blankName_returns_400() {
        Map<String, Object> body = payload(flightA, "ignored");
        body.put("testName", "  ");
        ResponseEntity<String> res = post(BASE, body, adminA);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_omittingOptionalBooleans_returns_201_with_legacy_defaults() {
        Map<String, Object> body = new HashMap<>();
        body.put("flightId", FlightId.of(flightA).toExternal());
        body.put("testName", "No-flags harness");
        ResponseEntity<String> created = post(BASE, body, adminA);
        assertThat(created.getStatusCode())
                .as("every optional flag is @Nullable Boolean, so the toggles the SPA never sends "
                        + "deserialise instead of tripping Jackson's FAIL_ON_NULL_FOR_PRIMITIVES")
                .isEqualTo(HttpStatus.CREATED);

        String id = readJson(created).get("id").asText();
        ResponseEntity<String> got = get(BASE + "/" + id, adminA);
        JsonNode detail = readJson(got);
        assertThat(detail.get("active").asBoolean()).isTrue();
        assertThat(detail.get("mustNotCreateDeliveryForFlight").asBoolean()).isFalse();
        assertThat(detail.get("ignoreRecipientName").asBoolean()).isFalse();
        assertThat(detail.get("ignoreItemAdditionalInformation").asBoolean()).isFalse();
    }

    @Test
    void create_writes_an_audit_row() {
        ResponseEntity<String> created = post(BASE, payload(flightA, "Audited harness"), adminA);
        String id = readJson(created).get("id").asText();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT action FROM t_mutation_audit_event "
                        + "WHERE tenant_club_id = ?::uuid AND target_entity_id = ?::uuid "
                        + "AND target_entity_type = 'DeliveryCreationTest'",
                clubA.toString(), id);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("action")).isEqualTo("CREATE");
    }


    private static Map<String, Object> payload(UUID flightId, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("flightId", FlightId.of(flightId).toExternal());
        body.put("testName", name);
        body.put("description", "daily tuning tool");
        body.put("active", true);
        body.put("mustNotCreateDeliveryForFlight", true);
        body.put("ignoreItemText", true);
        return body;
    }

    private UUID seedGliderFlight(UUID clubId) {
        UUID aircraftId = seedAircraft(clubId);
        FlightOperationalData ops = new FlightOperationalData(
                null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, (short) 0, (short) 0,
                false, false, null, null, null, null, null, null, null, null, false);
        return TenantTestContext.runAs(clubId, () ->
                flights.save(Flight.createGlider(aircraftId, FlightProcessState.VALID.id(), ops)).getId());
    }

    private UUID seedAircraft(UUID managingClubId) {
        UUID acType = jdbc.queryForObject("SELECT id FROM t_aircraft_type LIMIT 1", UUID.class);
        String immatriculation = "HB-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        Aircraft a = Aircraft.register(managingClubId, managingClubId, acType,
                immatriculation, null, null, null, null, null, null, null, null, null, 2,
                null, null, null, null, null, false, false, false, false, null, null);
        return aircraft.save(a).getId().value();
    }

    private String adminTokenFor(UUID clubId) {
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

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }
}
