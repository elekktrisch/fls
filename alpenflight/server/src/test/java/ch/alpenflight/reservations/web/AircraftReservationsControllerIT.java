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

/**
 * Full-stack HTTP integration test for the AircraftReservation CRUD slice
 * (J-5 T-05). Proves the web-layer wire contract: create → 201 + Location +
 * get round-trip; same-aircraft overlap create → 409; timed end ≤ start → 422.
 * The persistence-level conflict + tenant proof lives in
 * {@code AircraftReservationRepositoryIT} (T-04); the pure overlap predicate in
 * the domain unit test (T-03). Driven as a CLUB_ADMINISTRATOR of seed-club-1 —
 * authz is legacy-open ({@code isAuthenticated()}), so the role is incidental.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class AircraftReservationsControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLUB_ID = "019e30c3-2c00-7001-8000-000000000001";
    /** A foil tenant the test provisions — the tenant-isolation case for the validate probe. */
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
        // Pre-clean (ADR 0021): reservations FK to aircraft (RESTRICT), so they
        // must go before the aircraft rows seeded under this club are cleared.
        // CLUB_ID is the V31 dev-seed club, so the type pre-clean MUST exclude the
        // seed-band `Allgemein` row (`019e30c3-…`): deleting it would erase the V31
        // dev-seed in the SHARED Testcontainers DB and red ReservationsBaselineIT's
        // `…only_the_dev_seed_present` (J-5 T-34). This IT only ever creates its own
        // random-UUID `Flight` type, so scoping the delete to non-seed-band rows is
        // exact (cleans this IT's rows, leaves the V31 seed intact).
        jdbc.update("DELETE FROM t_aircraft_reservation WHERE operating_club_id = ?::uuid", CLUB_ID);
        // The tenant-isolation case seeds an other-club booking on club-1's aircraft;
        // clear it too so the shared Testcontainers DB doesn't carry it between runs.
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
        // FK ids are the typed-id external form (`ac-`/`pn-`/`loc-`) — matches
        // the masterdata pickers + FlightCreateRequest (J-5 T-25).
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

        // 10:30–10:45 overlaps the 10:00–11:00 booking on the SAME aircraft.
        ResponseEntity<String> overlap = post("/api/v1/aircraft-reservations",
                timedPayload("2026-07-01T10:30:00Z", "2026-07-01T10:45:00Z"));
        assertThat(overlap.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(readJson(overlap).get("key").asText()).isEqualTo("aircraft.reservation.overlap");
    }

    @Test
    void create_timedEndBeforeStart_returns_422() {
        ResponseEntity<String> res = post("/api/v1/aircraft-reservations",
                timedPayload("2026-07-01T11:00:00Z", "2026-07-01T10:00:00Z"));
        // 422 — the reason-phrase constant was aliased UNPROCESSABLE_ENTITY →
        // UNPROCESSABLE_CONTENT, so assert on the numeric code, not the enum.
        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(readJson(res).get("key").asText()).isEqualTo("aircraft.reservation.duration");
    }

    @Test
    void page_returns_camelCaseEnvelope_withTenantScopedItems_sortedByStart() {
        // Two non-overlapping bookings on the same aircraft; created out of
        // start order to prove the page sorts by start asc.
        post("/api/v1/aircraft-reservations",
                timedPayload("2026-07-02T10:00:00Z", "2026-07-02T11:00:00Z"));
        post("/api/v1/aircraft-reservations",
                timedPayload("2026-07-01T10:00:00Z", "2026-07-01T11:00:00Z"));

        ResponseEntity<String> res = post("/api/v1/aircraft-reservations/page/0/10",
                Map.of("sorting", Map.of("start", "asc")));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode page = readJson(res);
        // camelCase envelope (NOT legacy PascalCase Items/TotalRows).
        assertThat(page.has("items")).isTrue();
        assertThat(page.get("pageStart").asInt()).isZero();
        assertThat(page.get("pageSize").asInt()).isEqualTo(10);
        assertThat(page.get("totalRows").asLong()).isEqualTo(2);

        JsonNode items = page.get("items");
        assertThat(items).hasSize(2);
        // Sorted by start asc — earliest (2026-07-01) first.
        assertThat(items.get(0).get("start").asText()).isEqualTo("2026-07-01T10:00:00Z");
        assertThat(items.get(1).get("start").asText()).isEqualTo("2026-07-02T10:00:00Z");
        // Row carries the FK ids (typed-id external form) + same-module type name.
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
        // A persisted 10:00–11:00 booking on the aircraft …
        ResponseEntity<String> created = post("/api/v1/aircraft-reservations",
                timedPayload("2026-08-02T10:00:00Z", "2026-08-02T11:00:00Z"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // … a candidate 10:30–10:45 on the SAME aircraft is invalid (overlap),
        // surfaced on the start field — WITHOUT a save (no new row persisted).
        ResponseEntity<String> res = post("/api/v1/aircraft-reservations/validate",
                validatePayload("2026-08-02T10:30:00Z", "2026-08-02T10:45:00Z", null));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode result = readJson(res);
        assertThat(result.get("valid").asBoolean()).isFalse();
        assertThat(result.get("field").asText()).isEqualTo("start");
        assertThat(result.get("message").asText()).isNotBlank();
        // Non-mutating: only the one persisted booking exists.
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

        // Re-validating the SAME slot while excluding the reservation's own id
        // must NOT self-conflict.
        ResponseEntity<String> res = post("/api/v1/aircraft-reservations/validate",
                validatePayload("2026-08-03T10:00:00Z", "2026-08-03T11:00:00Z", ownId));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(res).get("valid").asBoolean()).isTrue();
    }

    @Test
    void validate_otherClubsReservation_doesNotConflict_tenantScoped() {
        // Provision a foil tenant (reusing seed-club-1's country + state), then a
        // conflicting booking on the SAME aircraft under it. The aircraft FK
        // crosses tenants (legacy-open), so this insert is FK-valid; the
        // reservation is stamped to the other club.
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

        // The caller is club-1; the other club's overlapping booking is invisible
        // to its tenant-scoped overlap probe → valid.
        ResponseEntity<String> res = post("/api/v1/aircraft-reservations/validate",
                validatePayload("2026-08-04T10:30:00Z", "2026-08-04T10:45:00Z", null));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(res).get("valid").asBoolean()).isTrue();
    }

    @Test
    void future_excludesPastReservations_sortedByStart() {
        // One reservation safely in the past, one safely in the future relative
        // to the test-run clock — /future returns only the future one.
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
        // T-18: the type list projection must carry instructorRequired so the
        // reservation form's conditional Second-Crew rule (required when the type
        // requires a second crew member) can evaluate client-side. AlpenFlight's
        // reservation-type model collapses legacy's three FlightType-derived flags
        // into the single is_instructor_required column.
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

    // ----- payload + seed helpers -----

    private Map<String, Object> timedPayload(String startIso, String endIso) {
        Map<String, Object> m = new LinkedHashMap<>();
        // The masterdata pickers serialize typed ids (`ac-`/`pn-`/`loc-`); the
        // create request DTO now binds those typed ids (J-5 T-25). The type id
        // stays a plain UUID (its listitems emit plain UUIDs).
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
