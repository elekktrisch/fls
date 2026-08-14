package ch.alpenflight.reservations.web;

import static org.assertj.core.api.Assertions.assertThat;

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

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class AircraftReservationsControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLUB_ID = "019e30c3-2c00-7001-8000-000000000001";
    private static final String OTHER_CLUB_ID = "019e30c3-2c00-7001-8000-0000000000f2";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    private String token;
    private UUID aircraftId;
    private UUID pilotId;
    private UUID locationId;
    private UUID reservationTypeId;

    @BeforeEach
    void seedAndAuth() {
        token = jwts.mint(c -> c
                .claim("clubId", CLUB_ID)
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
        jdbc.update("DELETE FROM t_aircraft_reservation WHERE operating_club_id = ?::uuid", CLUB_ID);
        jdbc.update("DELETE FROM t_aircraft_reservation WHERE operating_club_id = ?::uuid", OTHER_CLUB_ID);
        jdbc.update("DELETE FROM t_aircraft_reservation_type WHERE operating_club_id = ?::uuid "
                + "AND id::text NOT LIKE '019e30c3-%'", CLUB_ID);
        jdbc.update("DELETE FROM t_aircraft WHERE managing_club_id = ?::uuid "
                + "AND immatriculation LIKE 'HB-RV%'", CLUB_ID);

        aircraftId = seedAircraft();
        pilotId = seedPerson();
        locationId = seedLocation();
        reservationTypeId = seedReservationType();
    }

    @Test
    void create_returns_201_with_location_and_getRoundTrips() {
        Map<String, Object> body = timedPayload(
                "2026-07-01T10:00:00Z", "2026-07-01T11:00:00Z");
        ResponseEntity<String> res = post("/api/v1/aircraft-reservations", body);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode created = readJson(res);
        String id = created.get("id").asText();
        assertThat(created.get("aircraftId").asText()).isEqualTo("ac-" + aircraftId);
        assertThat(created.get("isAllDay").asBoolean()).isFalse();
        URI loc = res.getHeaders().getLocation();
        assertThat(loc).isNotNull();
        assertThat(loc.getPath()).isEqualTo("/api/v1/aircraft-reservations/" + id);

        ResponseEntity<String> got = get("/api/v1/aircraft-reservations/" + id);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode detail = readJson(got);
        assertThat(detail.get("id").asText()).isEqualTo(id);
        assertThat(detail.get("pilotPersonId").asText()).isEqualTo("pn-" + pilotId);
        assertThat(detail.get("start").asText()).isEqualTo("2026-07-01T10:00:00Z");
    }

    @Test
    void create_overlappingSameAircraft_returns_409() {
        ResponseEntity<String> first = post("/api/v1/aircraft-reservations",
                timedPayload("2026-07-01T10:00:00Z", "2026-07-01T11:00:00Z"));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> overlap = post("/api/v1/aircraft-reservations",
                timedPayload("2026-07-01T10:30:00Z", "2026-07-01T10:45:00Z"));
        assertThat(overlap.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(readJson(overlap).get("key").asText()).isEqualTo("aircraft.reservation.overlap");
    }

    @Test
    void create_timedEndBeforeStart_returns_422() {
        ResponseEntity<String> res = post("/api/v1/aircraft-reservations",
                timedPayload("2026-07-01T11:00:00Z", "2026-07-01T10:00:00Z"));
        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(readJson(res).get("key").asText()).isEqualTo("aircraft.reservation.duration");
    }

    @Test
    void page_returns_camelCaseEnvelope_withTenantScopedItems_sortedByStart() {
        post("/api/v1/aircraft-reservations",
                timedPayload("2026-07-02T10:00:00Z", "2026-07-02T11:00:00Z"));
        post("/api/v1/aircraft-reservations",
                timedPayload("2026-07-01T10:00:00Z", "2026-07-01T11:00:00Z"));

        ResponseEntity<String> res = post("/api/v1/aircraft-reservations/page/0/10",
                Map.of("sorting", Map.of("start", "asc")));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode page = readJson(res);
        assertThat(page.has("items")).isTrue();
        assertThat(page.get("pageStart").asInt()).isZero();
        assertThat(page.get("pageSize").asInt()).isEqualTo(10);
        assertThat(page.get("totalRows").asLong()).isEqualTo(2);

        JsonNode items = page.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("start").asText()).isEqualTo("2026-07-01T10:00:00Z");
        assertThat(items.get(1).get("start").asText()).isEqualTo("2026-07-02T10:00:00Z");
        assertThat(items.get(0).get("aircraftId").asText()).isEqualTo("ac-" + aircraftId);
        assertThat(items.get(0).get("reservationTypeName").asText()).isEqualTo("Flight");
    }

    @Test
    void validate_noOverlap_returnsValid() {
        Map<String, Object> req = validatePayload(
                "2026-08-01T10:00:00Z", "2026-08-01T11:00:00Z", null);
        ResponseEntity<String> res = post("/api/v1/aircraft-reservations/validate", req);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(res).get("valid").asBoolean()).isTrue();
    }

    @Test
    void validate_overlappingSlot_returnsInvalidWithField() {
        ResponseEntity<String> created = post("/api/v1/aircraft-reservations",
                timedPayload("2026-08-02T10:00:00Z", "2026-08-02T11:00:00Z"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> res = post("/api/v1/aircraft-reservations/validate",
                validatePayload("2026-08-02T10:30:00Z", "2026-08-02T10:45:00Z", null));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode result = readJson(res);
        assertThat(result.get("valid").asBoolean()).isFalse();
        assertThat(result.get("field").asText()).isEqualTo("start");
        assertThat(result.get("message").asText()).isNotBlank();
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM t_aircraft_reservation WHERE operating_club_id = ?::uuid "
                        + "AND deleted_on IS NULL", Integer.class, CLUB_ID);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void validate_editingOwnSlot_isSelfExcluded_returnsValid() {
        ResponseEntity<String> created = post("/api/v1/aircraft-reservations",
                timedPayload("2026-08-03T10:00:00Z", "2026-08-03T11:00:00Z"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String ownId = readJson(created).get("id").asText();

        ResponseEntity<String> res = post("/api/v1/aircraft-reservations/validate",
                validatePayload("2026-08-03T10:00:00Z", "2026-08-03T11:00:00Z", ownId));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(res).get("valid").asBoolean()).isTrue();
    }

    @Test
    void validate_otherClubsReservation_doesNotConflict_tenantScoped() {
        jdbc.update("""
                INSERT INTO t_club (id, clubname, club_key, country_id, club_state_id,
                        slug, public_registration_enabled)
                SELECT ?::uuid, 'Foil Club', 'FOIL', country_id, club_state_id,
                        'foil-club-f2', false
                FROM t_club WHERE id = ?::uuid
                ON CONFLICT (id) DO NOTHING
                """, OTHER_CLUB_ID, CLUB_ID);
        jdbc.update("""
                INSERT INTO t_aircraft_reservation (id, operating_club_id, aircraft_id,
                        reservation_start, reservation_end, is_all_day, pilot_person_id, location_id)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::timestamptz, ?::timestamptz, false, ?::uuid, ?::uuid)
                """,
                UUID.randomUUID().toString(), OTHER_CLUB_ID, aircraftId.toString(),
                "2026-08-04T10:00:00Z", "2026-08-04T11:00:00Z",
                pilotId.toString(), locationId.toString());

        ResponseEntity<String> res = post("/api/v1/aircraft-reservations/validate",
                validatePayload("2026-08-04T10:30:00Z", "2026-08-04T10:45:00Z", null));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(res).get("valid").asBoolean()).isTrue();
    }

    @Test
    void future_excludesPastReservations_sortedByStart() {
        post("/api/v1/aircraft-reservations",
                timedPayload("2000-01-01T10:00:00Z", "2000-01-01T11:00:00Z"));
        post("/api/v1/aircraft-reservations",
                timedPayload("2999-01-01T10:00:00Z", "2999-01-01T11:00:00Z"));

        ResponseEntity<String> res = get("/api/v1/aircraft-reservations/future");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode items = readJson(res);
        assertThat(items.isArray()).isTrue();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("start").asText()).isEqualTo("2999-01-01T10:00:00Z");
    }

    @Test
    void typeListitems_carryInstructorRequired_drivingConditionalSecondCrew() {
        UUID instructorType = seedReservationType("Instruction", true);
        UUID soloType = seedReservationType("Solo", false);

        JsonNode items = readJson(get("/api/v1/aircraft-reservation-types"));
        assertThat(items.isArray()).isTrue();

        JsonNode instructor = findById(items, instructorType);
        JsonNode solo = findById(items, soloType);
        assertThat(instructor).as("the instructor-required type is listed").isNotNull();
        assertThat(solo).as("the solo type is listed").isNotNull();
        assertThat(instructor.has("instructorRequired")).isTrue();
        assertThat(instructor.get("instructorRequired").asBoolean()).isTrue();
        assertThat(solo.get("instructorRequired").asBoolean()).isFalse();
    }

    @org.jspecify.annotations.Nullable
    private static JsonNode findById(JsonNode items, UUID id) {
        for (JsonNode row : items) {
            if (id.toString().equals(row.path("id").asText())) {
                return row;
            }
        }
        return null;
    }


    private Map<String, Object> timedPayload(String startIso, String endIso) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("aircraftId", "ac-" + aircraftId);
        m.put("pilotPersonId", "pn-" + pilotId);
        m.put("locationId", "loc-" + locationId);
        m.put("reservationTypeId", reservationTypeId.toString());
        m.put("start", startIso);
        m.put("end", endIso);
        m.put("isAllDay", false);
        return m;
    }

    private Map<String, Object> validatePayload(String startIso, String endIso,
                                                @org.jspecify.annotations.Nullable String excludeId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("aircraftId", "ac-" + aircraftId);
        m.put("start", startIso);
        m.put("end", endIso);
        m.put("isAllDay", false);
        if (excludeId != null) {
            m.put("excludeReservationId", excludeId);
        }
        return m;
    }

    private UUID seedAircraft() {
        UUID id = UUID.randomUUID();
        UUID aircraftTypeId = jdbc.queryForObject(
                "SELECT id FROM t_aircraft_type LIMIT 1", UUID.class);
        String immat = ("HB-RV" + Long.toString(System.nanoTime(), 36).substring(0, 6))
                .toUpperCase(java.util.Locale.ROOT);
        jdbc.update("""
                INSERT INTO t_aircraft (id, managing_club_id, owner_club_id, aircraft_type_id,
                        immatriculation, is_towing_or_winch_required, is_towing_start_allowed,
                        is_winch_start_allowed, is_towing_aircraft, is_fast_entry_record, nr_of_seats)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, false, false, false, false, false, 2)
                """,
                id.toString(), CLUB_ID, CLUB_ID, aircraftTypeId.toString(), immat);
        return id;
    }

    private UUID seedPerson() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO t_person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                id.toString(), "Res", "Pilot");
        return id;
    }

    private UUID seedLocation() {
        UUID id = UUID.randomUUID();
        UUID countryId = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        UUID locationTypeId = jdbc.queryForObject(
                "SELECT id FROM t_location_type LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO t_location (id, club_id, location_name, country_id, location_type_id,
                        is_inbound_route_required, is_outbound_route_required, is_fast_entry_record)
                VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, false, false, false)
                """,
                id.toString(), CLUB_ID, "RV-Home", countryId.toString(), locationTypeId.toString());
        return id;
    }

    private UUID seedReservationType() {
        return seedReservationType("Flight", false);
    }

    private UUID seedReservationType(String name, boolean instructorRequired) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_aircraft_reservation_type (id, operating_club_id, reservation_type_name,
                        is_instructor_required, is_maintenance, is_active)
                VALUES (?::uuid, ?::uuid, ?, ?, false, true)
                """,
                id.toString(), CLUB_ID, name, instructorRequired);
        return id;
    }

    private ResponseEntity<String> get(String path) {
        return rest.exchange(authed(RequestEntity.get(URI.create(path))).build(), String.class);
    }

    private ResponseEntity<String> post(String path, Object body) {
        return rest.exchange(
                authed(RequestEntity.post(URI.create(path)).contentType(MediaType.APPLICATION_JSON))
                        .body(body),
                String.class);
    }

    private <T extends RequestEntity.BodyBuilder> T authed(T builder) {
        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return builder;
    }

    private RequestEntity.HeadersBuilder<?> authed(RequestEntity.HeadersBuilder<?> builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }
}
