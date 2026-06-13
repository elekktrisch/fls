package ch.alpenflight.me.web;

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
 * HTTP slice for {@code GET /api/v1/me/system-dashboard} (J-3 T-10) — the
 * sysadmin dashboard variant's cross-tenant tile counts (consumed by T-11).
 *
 * <p>Deliberately the OPPOSITE of {@code ClubDashboardControllerIT}: the
 * totals must span ALL clubs, never the caller's tenant. The shared
 * Testcontainers DB already carries rows from sibling ITs, so the assertions
 * are <strong>floor-based</strong> (per the T-03c isolation lesson): seed a
 * known two-club delta and assert the totals are at least that — and, for the
 * cross-tenant proof, strictly larger than club A's own slice alone.
 *
 * <ul>
 *   <li><b>cross-tenant</b> — a club-A sysadmin's totals include club B's
 *       flights/users (the count is not scoped to the caller's tenant);</li>
 *   <li><b>authz</b> — SYSTEM_ADMINISTRATOR required (a club-admin / pilot is
 *       rejected 403);</li>
 *   <li><b>tenant-less principal</b> — a sysadmin JWT with NO {@code clubId}
 *       claim (the real shape per the J-2 audit work) can still call it.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class SystemDashboardControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID LANG_DE = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    // Distinct from the other flight ITs so a shared JVM run doesn't collide.
    private UUID clubA;
    private UUID clubB;
    private String aircraftAExternal;
    private String aircraftBExternal;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "sdash", "SDSH");
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
        cleanUserRows(clubA, clubB);
        aircraftAExternal = "ac-" + seedAircraft(clubA);
        aircraftBExternal = "ac-" + seedAircraft(clubB);
    }

    @Test
    void totals_span_all_clubs_not_scoped_to_caller() {
        // Club A: 2 flights; Club B: 3 flights. A tenant-scoped count from
        // club A's perspective would see only 2 — the sysadmin total must
        // include club B's 3 as well.
        String adminA = adminToken(clubA);
        createFlight(adminA, aircraftAExternal);
        createFlight(adminA, aircraftAExternal);

        String adminB = adminToken(clubB);
        createFlight(adminB, aircraftBExternal);
        createFlight(adminB, aircraftBExternal);
        createFlight(adminB, aircraftBExternal);

        // A user in each club (cross-tenant — no @TenantId on User).
        seedUser(clubA, "sdash-a");
        seedUser(clubB, "sdash-b1");
        seedUser(clubB, "sdash-b2");

        JsonNode body = get(sysadminToken());

        assertThat(body.get("totalClubs").asLong())
                .as("at least the two seeded clubs (shared DB may hold more)")
                .isGreaterThanOrEqualTo(2L);
        assertThat(body.get("totalUsers").asLong())
                .as("at least the three seeded users, spanning both clubs")
                .isGreaterThanOrEqualTo(3L);
        // The cross-tenant proof: 5 flights live across A (2) + B (3). A
        // count scoped to club A would report 2; the total must include B's.
        assertThat(body.get("totalFlights").asLong())
                .as("spans both clubs (A's 2 + B's 3), not just the caller's slice")
                .isGreaterThanOrEqualTo(5L);
    }

    @Test
    void requires_system_administrator_role_others_403() {
        String clubAdmin = adminToken(clubA);
        assertThat(rawGet(clubAdmin).getStatusCode())
                .as("a CLUB_ADMINISTRATOR is not a sysadmin")
                .isEqualTo(HttpStatus.FORBIDDEN);

        String pilot = jwts.mint(c -> c
                .claim("clubId", clubA.toString())
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));
        assertThat(rawGet(pilot).getStatusCode())
                .as("a PILOT is not a sysadmin")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void tenant_less_sysadmin_principal_can_call_it() {
        // A real SYSTEM_ADMINISTRATOR carries NO clubId claim (sysadmins are
        // tenant-less per the J-2 audit work). The endpoint must still serve.
        ResponseEntity<String> res = rawGet(sysadminToken());
        assertThat(res.getStatusCode())
                .as("a tenant-less sysadmin principal is served, not rejected")
                .isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.has("totalClubs")).isTrue();
        assertThat(body.has("totalUsers")).isTrue();
        assertThat(body.has("totalFlights")).isTrue();
    }

    /** Tenant-less sysadmin token — no {@code clubId} claim. */
    private String sysadminToken() {
        return jwts.mint(c -> c
                .claim("realm_access", Map.of("roles", List.of("SYSTEM_ADMINISTRATOR"))));
    }

    private String adminToken(UUID clubId) {
        return jwts.mint(c -> c
                .claim("clubId", clubId.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    private void createFlight(String token, String aircraftExternal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flightAircraftType", "GLIDER");
        body.put("aircraftId", aircraftExternal);
        body.put("flightDate", TODAY.toString());
        body.put("isSoloFlight", false);
        body.put("noStartTimeInformation", false);
        body.put("noLdgTimeInformation", false);
        body.put("crew", List.of());
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.post(URI.create("/api/v1/flights"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .body(body),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void seedUser(UUID clubId, String username) {
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, person_id,
                                    notification_email, language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, NULL, ?, ?::uuid, ?::uuid)
                """,
                UUID.randomUUID().toString(), clubId.toString(),
                username, username, username + "@example.com",
                LANG_DE.toString(), UUID.randomUUID().toString());
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
                "HB-SD" + Integer.toHexString(id.hashCode() & 0xfff));
        return id;
    }

    private void cleanUserRows(UUID... clubIds) {
        for (UUID clubId : clubIds) {
            jdbc.update("DELETE FROM t_user WHERE club_id = ?::uuid", clubId.toString());
        }
    }

    private JsonNode get(String token) {
        return readJson(rawGet(token));
    }

    private ResponseEntity<String> rawGet(String token) {
        return rest.exchange(
                RequestEntity.get(URI.create("/api/v1/me/system-dashboard"))
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
