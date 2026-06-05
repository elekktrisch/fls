package ch.alpenflight.me.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
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
 * HTTP slice for {@code GET /api/v1/me/club-dashboard} (J-3 T-08) — the
 * club-admin dashboard tile counts. Proves the four contracts the dashboard
 * variant (T-09) depends on, against isolated per-IT data (NOT the showcase
 * seed), keeping the count semantics identical: today by {@code flight_date};
 * pending = {@code NotProcessed} + {@code Invalid}.
 *
 * <ul>
 *   <li><b>today-count</b> counts only flights flown today, caller's club;</li>
 *   <li><b>pending</b> = NotProcessed + Invalid only (Valid / others excluded);</li>
 *   <li><b>tenant isolation</b> — a second club's flights never leak in;</li>
 *   <li><b>authz</b> — CLUB_ADMINISTRATOR required (a pilot is rejected 403).</li>
 * </ul>
 *
 * <p>Flights are created through the public POST (stamps NotProcessed + the
 * chosen flight_date) then forced into a target process state by a direct
 * by-PK JDBC update — the same seeding pattern as {@code
 * FlightProcessStatePatchIT} (the public surface only stamps NotProcessed on
 * create; system transitions live in deferred stories). "Today" is the server
 * clock's today, so the today-dated rows are created with {@link LocalDate#now()}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class ClubDashboardControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Distinct from the other flight ITs so a shared JVM run doesn't collide.
    private static final UUID CLUB_A = UUID.fromString("019e30c3-2c00-7001-8000-0000000000c1");
    private static final UUID CLUB_B = UUID.fromString("019e30c3-2c00-7001-8000-0000000000c2");

    private static final UUID INVALID = UUID.fromString("019e2e15-2c00-7a99-8000-000000003a99");
    private static final UUID VALID = UUID.fromString("019e2e15-2c00-7a9a-8000-000000003a9a");

    // Server resolves "today" in UTC (FlightGatePolicy.ZONE / LocalDate.now(clock)
    // on a UTC clock), so the today-dated rows must use the same zone — using the
    // box's default zone could diverge from the server's UTC today near midnight.
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final LocalDate PAST = TODAY.minusDays(30);

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    private String aircraftAExternal;

    @BeforeEach
    void seed() {
        cleanFlightRows(CLUB_A, CLUB_B);
        new TwoClubFixture(jdbc, CLUB_A, CLUB_B, "cdash", "CDSH").seed();
        aircraftAExternal = "ac-" + seedAircraft(CLUB_A);
    }

    @Test
    void clubAdmin_counts_today_and_pending_scoped_to_own_club() {
        String adminA = adminToken(CLUB_A);
        String aircraftB = "ac-" + seedAircraft(CLUB_B);

        // Club A: 3 flights today (2 NotProcessed + 1 Valid) → today = 3.
        createFlight(adminA, aircraftAExternal, TODAY, null);           // today, NotProcessed
        createFlight(adminA, aircraftAExternal, TODAY, null);           // today, NotProcessed
        createFlight(adminA, aircraftAExternal, TODAY, VALID);          // today, Valid
        // Club A: past-dated rows exercising the pending set boundary.
        createFlight(adminA, aircraftAExternal, PAST, INVALID);        // past, Invalid  → pending
        createFlight(adminA, aircraftAExternal, PAST, VALID);          // past, Valid    → NOT pending
        // pending (NotProcessed + Invalid) = 2 + 1 = 3; Valid rows excluded.

        // Club B noise: a flight today + a NotProcessed — must NOT leak into A's counts.
        String adminB = adminToken(CLUB_B);
        createFlight(adminB, aircraftB, TODAY, null);
        createFlight(adminB, aircraftB, PAST, INVALID);

        JsonNode body = get("/api/v1/me/club-dashboard", adminA);
        assertThat(body.get("todaysFlights").asLong())
                .as("today's flights = only club A's flight_date=today rows")
                .isEqualTo(3L);
        assertThat(body.get("pendingValidation").asLong())
                .as("pending = club A's NotProcessed (2 today) + Invalid (1 past); Valid excluded")
                .isEqualTo(3L);
    }

    @Test
    void counts_are_tenant_isolated_clubB_sees_only_its_own() {
        // Club A has flights; Club B has exactly one (today, NotProcessed).
        String adminA = adminToken(CLUB_A);
        createFlight(adminA, aircraftAExternal, TODAY, null);
        createFlight(adminA, aircraftAExternal, TODAY, null);

        String aircraftB = "ac-" + seedAircraft(CLUB_B);
        String adminB = adminToken(CLUB_B);
        createFlight(adminB, aircraftB, TODAY, null);

        JsonNode body = get("/api/v1/me/club-dashboard", adminB);
        assertThat(body.get("todaysFlights").asLong())
                .as("club B sees only its own one flight, not club A's two")
                .isEqualTo(1L);
        assertThat(body.get("pendingValidation").asLong()).isEqualTo(1L);
    }

    @Test
    void pilot_without_clubAdmin_role_is_rejected_403() {
        String pilot = jwts.mint(c -> c
                .claim("clubId", CLUB_A.toString())
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));
        ResponseEntity<String> res = rawGet("/api/v1/me/club-dashboard", pilot);
        assertThat(res.getStatusCode())
                .as("the club-admin dashboard is CLUB_ADMINISTRATOR-only")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private String adminToken(UUID clubId) {
        return jwts.mint(c -> c
                .claim("clubId", clubId.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    /**
     * Creates a flight via the public POST (NotProcessed) on the given date,
     * then forces {@code targetState} via a by-PK JDBC update when non-null.
     */
    private void createFlight(String token, String aircraftExternal, LocalDate date,
                              UUID targetState) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flightAircraftType", "GLIDER");
        body.put("aircraftId", aircraftExternal);
        body.put("flightDate", date.toString());
        body.put("isSoloFlight", false);
        body.put("noStartTimeInformation", false);
        body.put("noLdgTimeInformation", false);
        body.put("crew", List.of());
        ResponseEntity<String> res = post("/api/v1/flights", body, token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        if (targetState != null) {
            String idExternal = readJson(res).get("id").asText();
            UUID flightUuid = UUID.fromString(idExternal.substring("fl-".length()));
            jdbc.update("UPDATE t_flight SET process_state_id = ?::uuid WHERE id = ?::uuid",
                    targetState.toString(), flightUuid.toString());
        }
    }

    private UUID seedAircraft(UUID managingClubId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_aircraft (id, managing_club_id, owner_club_id, aircraft_type_id,
                                      immatriculation, is_towing_or_winch_required,
                                      is_towing_start_allowed, is_winch_start_allowed,
                                      is_towing_aircraft, is_fast_entry_record, nr_of_seats)
                VALUES (?::uuid, ?::uuid, ?::uuid, '019e2e15-2c00-7af9-8000-000000002af9',
                        ?, false, false, false, false, false, 2)
                """,
                id.toString(), managingClubId.toString(), managingClubId.toString(),
                "HB-CD" + Integer.toHexString(id.hashCode() & 0xfff));
        return id;
    }

    private void cleanFlightRows(UUID... clubIds) {
        for (UUID clubId : clubIds) {
            jdbc.update("DELETE FROM t_flight WHERE operating_club_id = ?::uuid",
                    clubId.toString());
        }
    }

    private ResponseEntity<String> post(String path, Object body, String token) {
        return rest.exchange(
                RequestEntity.post(URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .body(body),
                String.class);
    }

    private JsonNode get(String path, String token) {
        return readJson(rawGet(path, token));
    }

    private ResponseEntity<String> rawGet(String path, String token) {
        return rest.exchange(
                RequestEntity.get(URI.create(path))
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
