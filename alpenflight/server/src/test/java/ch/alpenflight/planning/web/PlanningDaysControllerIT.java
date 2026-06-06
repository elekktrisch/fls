package ch.alpenflight.planning.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * Full-stack HTTP integration test for the PlanningDay CRUD slice (J-6 T-04).
 * Proves the web-layer wire contract:
 *
 * <ul>
 *   <li>create → 201 + Location + get round-trips the 3 crew person ids (the
 *       load-bearing 3-picker-over-generic-rows shape);</li>
 *   <li>duplicate (date, location) for the same club → 409;</li>
 *   <li>delete as a non-admin non-creator → 403 (the admin-or-creator gate);</li>
 *   <li>cross-tenant GET → 404 ({@code @TenantId} isolation).</li>
 * </ul>
 *
 * <p>The persistence-level dedup + tenant proof lives in
 * {@code PlanningDayRepositoryIT} (T-03); the crew-assignment + date-range
 * invariant in the domain unit test (T-02). The three role assignment types are
 * seeded per-club by this IT (clean-seed T-06 is not yet wired).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class PlanningDaysControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // The V31 dev-seed club — JIT materialisation maps its seeded languages, so a
    // UUID-sub PILOT token auto-creates a t_user (driving created_by_user_id).
    private static final UUID CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final UUID OTHER_CLUB =
            UUID.fromString("019e30c6-2c00-7001-8000-0000000000f1");

    private static final LocalDate DAY = LocalDate.now(ZoneOffset.UTC).plusDays(3);

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    private String adminToken;
    private UUID locationId;
    private UUID instructorId;
    private UUID towingPilotId;
    private UUID flightOperatorId;
    private final List<UUID> mintedSubs = new java.util.ArrayList<>();

    @BeforeEach
    void seedAndAuth() {
        adminToken = jwts.mint(c -> c
                .claim("clubId", CLUB.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));

        // Clean this IT's own rows (RESTRICT FK to Location). Scope to the IT's
        // non-seed-band assignment types + the IT location so the V31 dev-seed
        // stays intact in the shared Testcontainers DB.
        jdbc.update("DELETE FROM t_planning_day WHERE operating_club_id = ?::uuid", CLUB.toString());
        jdbc.update("DELETE FROM t_planning_day_assignment_type WHERE operating_club_id = ?::uuid "
                + "AND assignment_type_name IN ('fluglehrer','schlepppilot','segelflugleiter')",
                CLUB.toString());
        jdbc.update("DELETE FROM t_location WHERE club_id = ?::uuid AND location_name LIKE 'PLN-IT-%'",
                CLUB.toString());

        locationId = seedLocation(CLUB);
        seedAssignmentType(CLUB, "fluglehrer");
        seedAssignmentType(CLUB, "schlepppilot");
        seedAssignmentType(CLUB, "segelflugleiter");
        instructorId = seedPerson("Instr");
        towingPilotId = seedPerson("Tow");
        flightOperatorId = seedPerson("Ops");
    }

    @org.junit.jupiter.api.AfterEach
    void cleanupUsers() {
        for (UUID sub : mintedSubs) {
            jdbc.update("DELETE FROM t_mutation_audit_event WHERE actor_keycloak_sub = ?", sub.toString());
            jdbc.update("DELETE FROM t_user WHERE keycloak_sub = ?::uuid", sub.toString());
        }
        mintedSubs.clear();
    }

    @Test
    void create_returns201_andGetRoundTripsTheThreeCrewPersonIds() {
        ResponseEntity<String> res = post("/api/v1/planning-days", fullCrewPayload(DAY));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode created = readJson(res);
        String id = created.get("id").asText();
        URI loc = res.getHeaders().getLocation();
        assertThat(loc).isNotNull();
        assertThat(loc.getPath()).isEqualTo("/api/v1/planning-days/" + id);

        ResponseEntity<String> got = get("/api/v1/planning-days/" + id, adminToken);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode detail = readJson(got);
        // The 3 nullable crew slots round-trip as typed person ids.
        assertThat(detail.get("instructorPersonId").asText()).isEqualTo("pn-" + instructorId);
        assertThat(detail.get("towingPilotPersonId").asText()).isEqualTo("pn-" + towingPilotId);
        assertThat(detail.get("flightOperatorPersonId").asText()).isEqualTo("pn-" + flightOperatorId);
        assertThat(detail.get("planningDate").asText()).isEqualTo(DAY.toString());
        assertThat(detail.get("locationId").asText()).isEqualTo("loc-" + locationId);
        // No reservations seeded for this day → computed count 0.
        assertThat(detail.get("numberOfAircraftReservations").asLong()).isZero();
        // Admin may mutate → both flags true.
        assertThat(detail.get("canUpdateRecord").asBoolean()).isTrue();
        assertThat(detail.get("canDeleteRecord").asBoolean()).isTrue();
    }

    @Test
    void create_duplicateClubDateLocation_returns409() {
        ResponseEntity<String> first = post("/api/v1/planning-days", fullCrewPayload(DAY));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> dup = post("/api/v1/planning-days", fullCrewPayload(DAY));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(readJson(dup).get("key").asText()).isEqualTo("planning.day.duplicate");
    }

    @Test
    void delete_asNonAdminNonCreator_returns403() {
        // Creator: a PILOT (non-admin) of the club — JIT materialises their user,
        // so the day's created_by_user_id is the creator's id.
        UUID creatorSub = freshSub();
        String creatorToken = pilotToken(creatorSub);
        ResponseEntity<String> created = post("/api/v1/planning-days", fullCrewPayload(DAY), creatorToken);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = readJson(created).get("id").asText();

        // A DIFFERENT PILOT (non-admin, non-creator) cannot delete → 403.
        UUID otherSub = freshSub();
        String otherToken = pilotToken(otherSub);
        ResponseEntity<String> denied = delete("/api/v1/planning-days/" + id, otherToken);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // The creator themselves may delete → 204 (proves the gate's OR-branch).
        ResponseEntity<String> ok = delete("/api/v1/planning-days/" + id, creatorToken);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void get_crossTenant_returns404() {
        ResponseEntity<String> created = post("/api/v1/planning-days", fullCrewPayload(DAY));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = readJson(created).get("id").asText();

        String otherClubToken = jwts.mint(c -> c
                .claim("clubId", OTHER_CLUB.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
        ResponseEntity<String> got = get("/api/v1/planning-days/" + id, otherClubToken);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ----- payload + seed helpers -----

    private Map<String, Object> fullCrewPayload(LocalDate day) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planningDate", day.toString());
        m.put("locationId", "loc-" + locationId);
        m.put("instructorPersonId", "pn-" + instructorId);
        m.put("towingPilotPersonId", "pn-" + towingPilotId);
        m.put("flightOperatorPersonId", "pn-" + flightOperatorId);
        m.put("info", "IT planning day");
        return m;
    }

    private String pilotToken(UUID sub) {
        mintedSubs.add(sub);
        return jwts.mintJitReady(sub, CLUB, c -> c
                .claim("locale", "en")
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));
    }

    private UUID freshSub() {
        return UUID.randomUUID();
    }

    private UUID seedLocation(UUID clubId) {
        UUID id = UUID.randomUUID();
        UUID countryId = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        UUID locationTypeId = jdbc.queryForObject("SELECT id FROM t_location_type LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO t_location (id, club_id, location_name, country_id, location_type_id,
                        is_inbound_route_required, is_outbound_route_required, is_fast_entry_record)
                VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, false, false, false)
                """,
                id.toString(), clubId.toString(), "PLN-IT-" + nano(),
                countryId.toString(), locationTypeId.toString());
        return id;
    }

    private void seedAssignmentType(UUID clubId, String name) {
        jdbc.update("""
                INSERT INTO t_planning_day_assignment_type
                    (id, operating_club_id, assignment_type_name, required_nr_of_assignments)
                VALUES (?::uuid, ?::uuid, ?, 1)
                """,
                UUID.randomUUID().toString(), clubId.toString(), name);
    }

    private UUID seedPerson(String firstName) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO t_person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                id.toString(), firstName, "PlnCrew");
        return id;
    }

    private static String nano() {
        return Long.toString(System.nanoTime(), 36);
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(authed(RequestEntity.get(URI.create(path)), token).build(), String.class);
    }

    private ResponseEntity<String> post(String path, Object body) {
        return post(path, body, adminToken);
    }

    private ResponseEntity<String> post(String path, Object body, String token) {
        return rest.exchange(
                authed(RequestEntity.post(URI.create(path)).contentType(MediaType.APPLICATION_JSON), token)
                        .body(body),
                String.class);
    }

    private ResponseEntity<String> delete(String path, String token) {
        return rest.exchange(authed(RequestEntity.delete(URI.create(path)), token).build(), String.class);
    }

    private <T extends RequestEntity.BodyBuilder> T authed(T builder, String token) {
        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return builder;
    }

    private RequestEntity.HeadersBuilder<?> authed(RequestEntity.HeadersBuilder<?> builder, String token) {
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
