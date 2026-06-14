package ch.alpenflight.accounting.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.application.AccountingRuleFilterDtos.AccountingRuleFilterWriteRequest;
import ch.alpenflight.accounting.application.AccountingRuleFiltersService;
import ch.alpenflight.accounting.domain.FilterConfig;
import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.flights.domain.CrewMemberSpec;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightCrewTypeIds;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonNotificationPrefs;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.persons.domain.PersonRoleFlags;
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
import java.time.Instant;
import java.time.ZoneOffset;
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

/**
 * Full-stack HTTP integration test for the harness engine endpoints (T-15): the
 * dry-run {@code GET /example/{flightId}} (run the engine, return the would-be
 * delivery, no persistence) and the run-test {@code POST /{id}/run} (engine vs
 * the stored expected set → pass/fail + the field-by-field diff). Seeds two clubs
 * + a glider flight (FlightTime tier + LandingTax filters → a deterministic
 * two-item delivery) via production factories, then drives the endpoints under a
 * CLUB_ADMINISTRATOR. Asserts: dry-run returns the engine's items without writing
 * the harness's run-state; run-test SUCCESS when the captured expected matches;
 * run-test FAILURE with the diff message when a quantity diverges; cross-tenant
 * run → 404 ({@code @TenantId} isolation through the HTTP layer).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class DeliveryCreationTestRunIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/deliverycreationtests";

    private static final UUID FILTER_TYPE_FLIGHT_TIME =
            UUID.fromString("019e2e15-2c00-7652-8000-000000004652");
    private static final UUID FILTER_TYPE_LANDING_TAX =
            UUID.fromString("019e2e15-2c00-7655-8000-000000004655");
    private static final UUID UNIT_MINUTES =
            UUID.fromString("019e2e15-2c00-7a38-8000-000000004a38");
    private static final UUID UNIT_LANDINGS =
            UUID.fromString("019e2e15-2c00-7a3a-8000-000000004a3a");
    private static final int LEGACY_FLIGHT_TIME = 30;
    private static final int LEGACY_LANDING_TAX = 60;

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired AccountingRuleFiltersService filtersService;
    @Autowired FlightRepository flights;
    @Autowired AircraftRepository aircraft;
    @Autowired PersonRepository persons;
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
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_DCTR_", "IT_DCTR");
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
        flightA = seedDeterministicFlight(clubA);
        seedLineFilters(clubA);
        adminA = adminTokenFor(clubA);
        adminB = adminTokenFor(clubB);
    }

    @Test
    void dryRun_returns_engine_items_without_persisting_a_run_state() {
        ResponseEntity<String> example = get(BASE + "/example/" + flightA, adminA);
        assertThat(example.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode body = readJson(example);
        JsonNode items = body.get("delivery").get("items");
        assertThat(items.size()).isEqualTo(2);
        assertThat(articleNumbers(items)).containsExactly("ART-FT", "ART-LT");
        assertThat(body.get("matchedFilterIds").size()).isEqualTo(2);

        // The dry-run created NO harness, so nothing was persisted by it.
        Long harnessCount = jdbc.queryForObject(
                "SELECT count(*) FROM t_delivery_creation_test WHERE operating_club_id = ?::uuid",
                Long.class, clubA.toString());
        assertThat(harnessCount).isZero();
    }

    @Test
    void runTest_succeeds_when_expected_matches_engine_output() {
        String id = createHarnessCapturingExpected();

        ResponseEntity<String> run = post(BASE + "/" + id + "/run", null, adminA);
        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(run);
        assertThat(body.get("lastTestSuccessful").asBoolean()).isTrue();
        assertThat(body.get("lastTestResultMessage").asText()).isEmpty();
        assertThat(body.get("lastTestCreatedDelivery").get("items").size()).isEqualTo(2);
        assertThat(body.get("lastTestMatchedFilterIds").size()).isEqualTo(2);

        // The run-state was persisted on the harness.
        ResponseEntity<String> got = get(BASE + "/" + id, adminA);
        assertThat(readJson(got).get("lastTestSuccessful").asBoolean()).isTrue();
    }

    @Test
    void runTest_fails_with_diff_message_when_expected_quantity_differs() {
        String id = createHarnessCapturingExpected();
        // Tamper the FlightTime expected item so the engine output diverges.
        bumpFirstExpectedItemQuantity(id);

        ResponseEntity<String> run = post(BASE + "/" + id + "/run", null, adminA);
        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(run);
        assertThat(body.get("lastTestSuccessful").asBoolean()).isFalse();
        assertThat(body.get("lastTestResultMessage").asText())
                .contains("Quantity")
                .contains("doesn't match");
    }

    @Test
    void runTest_crossTenant_returns_404() {
        String id = createHarnessCapturingExpected();
        ResponseEntity<String> run = post(BASE + "/" + id + "/run", null, adminB);
        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ----- harness authoring (dry-run fills expected, like the SPA edit form) -----

    private String createHarnessCapturingExpected() {
        JsonNode example = readJson(get(BASE + "/example/" + flightA, adminA));
        JsonNode delivery = example.get("delivery");

        ResponseEntity<String> created = post(BASE, harnessPayload(), adminA);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = readJson(created).get("id").asText();

        // Capture the dry-run as the expected set straight in the table (the SPA
        // does it via a follow-up save; the engine/diff seam under test doesn't
        // own a capture endpoint).
        jdbc.update(
                "UPDATE t_delivery_creation_test SET expected_delivery = ?::jsonb "
                        + "WHERE id = ?::uuid",
                delivery.toString(), id);
        return id;
    }

    private void bumpFirstExpectedItemQuantity(String id) {
        String expected = jdbc.queryForObject(
                "SELECT expected_delivery::text FROM t_delivery_creation_test WHERE id = ?::uuid",
                String.class, id);
        try {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(expected);
            var items = (com.fasterxml.jackson.databind.node.ArrayNode) node.get("items");
            var first = (com.fasterxml.jackson.databind.node.ObjectNode) items.get(0);
            double q = first.get("quantity").asDouble();
            first.put("quantity", q + 1);
            jdbc.update(
                    "UPDATE t_delivery_creation_test SET expected_delivery = ?::jsonb WHERE id = ?::uuid",
                    node.toString(), id);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to tamper expected_delivery", e);
        }
    }

    private Map<String, Object> harnessPayload() {
        Map<String, Object> body = new HashMap<>();
        body.put("flightId", flightA.toString());
        body.put("testName", "Run harness");
        body.put("active", true);
        // T-12 deferral: the engine leaves the two info texts NULL, so a green
        // harness ignores them; recipient + address have no expected values here.
        body.put("ignoreDeliveryInformation", true);
        body.put("ignoreAdditionalInformation", true);
        body.put("ignoreRecipientName", true);
        body.put("ignoreRecipientAddress", true);
        body.put("ignoreRecipientPersonId", true);
        body.put("ignoreRecipientClubMemberNumber", true);
        return body;
    }

    // ----- seeding (production-create paths) -----

    private void seedLineFilters(UUID clubId) {
        TenantTestContext.runAs(clubId, () -> {
            filtersService.create(lineRequest(FILTER_TYPE_FLIGHT_TIME, LEGACY_FLIGHT_TIME,
                    UNIT_MINUTES, "FT", "ART-FT", "Flugzeit"));
            filtersService.create(lineRequest(FILTER_TYPE_LANDING_TAX, LEGACY_LANDING_TAX,
                    UNIT_LANDINGS, "LT", "ART-LT", "Landetaxe"));
            return null;
        });
    }

    private static AccountingRuleFilterWriteRequest lineRequest(UUID typeId, int legacyType,
                                                                UUID unitTypeId, String name,
                                                                String article, String lineText) {
        FilterConfig config = new FilterConfig(
                true, false, false,
                false, false, false,
                false, false, false,
                null, 0, null, null, null,
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                null, null);
        return new AccountingRuleFilterWriteRequest(
                typeId, legacyType, unitTypeId, name, null,
                true, false, false,
                article, lineText, null, null, config);
    }

    private UUID seedDeterministicFlight(UUID clubId) {
        UUID aircraftId = seedAircraft(clubId);
        UUID flightType = seedFlightType(clubId);
        UUID pilot = seedMember(clubId, "REC-1");
        Instant start = Instant.parse("2026-05-15T08:00:00Z");
        Instant ldg = Instant.parse("2026-05-15T09:30:00Z");
        FlightOperationalData ops = new FlightOperationalData(
                start.atZone(ZoneOffset.UTC).toLocalDate(), start, ldg, null, null,
                null, null, null, null, null, null,
                flightType, null, (short) 2, (short) 0,
                false, false, null, null, null, null, null, null, null, null, false);
        UUID flightId = TenantTestContext.runAs(clubId, () ->
                flights.save(Flight.createGlider(aircraftId, FlightProcessState.VALID.id(), ops)).getId());
        TenantTestContext.runAs(clubId, () -> {
            Flight flight = flights.findByIdWithCrew(FlightId.of(flightId)).orElseThrow();
            flight.replaceCrew(List.of(new CrewMemberSpec(
                    pilot, FlightCrewTypeIds.PILOT_OR_STUDENT, null, null, null, null, null, null)));
            flights.save(flight);
        });
        return flightId;
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

    private UUID seedFlightType(UUID clubId) {
        ch.alpenflight.flighttypes.domain.FlightType flightType =
                ch.alpenflight.flighttypes.domain.FlightType.register("Schulung", "SCH",
                        false, false, false, false, false,
                        true, true, true,
                        false, false, false, null);
        return TenantTestContext.runAs(clubId,
                () -> flightTypeRepo.save(flightType).getId().value());
    }

    @Autowired ch.alpenflight.flighttypes.domain.FlightTypeRepository flightTypeRepo;

    private UUID seedMember(UUID clubId, String memberNumber) {
        Person person = Person.register("Petra", "Pilot", null);
        return TenantTestContext.runAs(clubId, () -> {
            person.joinClub(clubId, memberNumber, null,
                    PersonRoleFlags.none(), PersonNotificationPrefs.none(), true);
            return persons.save(person).getId().value();
        });
    }

    // ----- http -----

    private String adminTokenFor(UUID clubId) {
        return jwts.mint(c -> c
                .claim("clubId", clubId.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    private static List<String> articleNumbers(JsonNode items) {
        return List.of(items.get(0).get("articleNumber").asText(),
                items.get(1).get("articleNumber").asText());
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(
                RequestEntity.get(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).build(),
                String.class);
    }

    private ResponseEntity<String> post(String path, Object body, String token) {
        var builder = RequestEntity.post(URI.create(path))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        RequestEntity<?> req = body == null
                ? builder.build()
                : builder.contentType(MediaType.APPLICATION_JSON).body(body);
        return rest.exchange(req, String.class);
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }
}
