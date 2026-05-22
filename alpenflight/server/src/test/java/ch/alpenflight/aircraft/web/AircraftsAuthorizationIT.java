package ch.alpenflight.aircraft.web;

import static ch.alpenflight.aircraft.web.AircraftsTestFixtures.SEED_AIRCRAFT_STATE_OK;
import static ch.alpenflight.aircraft.web.AircraftsTestFixtures.createPayload;
import static ch.alpenflight.aircraft.web.AircraftsTestFixtures.uniqueImmatriculation;
import static ch.alpenflight.aircraft.web.AircraftsTestFixtures.updatePayload;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
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
 * Asserts the cross-tenant authz matrix for {@code AircraftAccess}:
 * Aircraft is cross-tenant, so Hibernate does NOT filter rows by club —
 * the SpEL bean is the only line between Club B and Club A's aircraft.
 *
 * <p>Matrix coverage:
 * <ul>
 *   <li>Reads — any authenticated principal of any club can see any
 *       active aircraft (full-fleet visibility is intentional).</li>
 *   <li>Writes (owned) — CLUB_ADMIN of the owning club + SYSTEM_ADMIN
 *       succeed; CLUB_ADMIN of a different club is 403.</li>
 *   <li>Writes (null-owner / charter) — SYSTEM_ADMIN-only; any
 *       CLUB_ADMIN is 403.</li>
 *   <li>Transfer-ownership — SYSTEM_ADMIN-only.</li>
 *   <li>State change — FLIGHT_OPS of the owning club + SYSTEM_ADMIN
 *       succeed; FLIGHT_OPS of a different club is 403.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class AircraftsAuthorizationIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLUB_A = "019e30c3-2c00-7001-8000-000000000001";
    private static final String CLUB_B = "019e30c3-2c00-7001-8000-000000000002";

    @Autowired TestRestTemplate rest;
    @Autowired JwtTestFixture jwts;
    @Autowired JdbcTemplate jdbc;

    /**
     * CLUB_A is seeded by V5 (walking skeleton). CLUB_B isn't, so the
     * transfer-ownership matrix needs to ensure the FK target exists.
     * Idempotent via {@code ON CONFLICT DO NOTHING}; other tests can run
     * before / after without interference.
     */
    @BeforeEach
    void seedClubB() {
        UUID countryId = jdbc.queryForObject("SELECT id FROM country LIMIT 1", UUID.class);
        UUID clubStateId = jdbc.queryForObject("SELECT id FROM club_state LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO club (id, clubname, club_key, country_id, club_state_id, slug, public_registration_enabled)
                VALUES (?::uuid, ?, ?, ?::uuid, ?::uuid, ?, false)
                ON CONFLICT (id) DO NOTHING
                """,
                CLUB_B,
                "Aircraft IT Test Club B",
                "ACIT_B",
                countryId.toString(),
                clubStateId.toString(),
                "aircraft-it-test-b");
    }

    @Test
    void clubAdminOfOtherClub_cannotMutate_ownedAircraft() {
        String sysToken = mintToken(CLUB_A, "SYSTEM_ADMINISTRATOR");
        // Sysadmin creates Aircraft owned by CLUB_A
        String imm = uniqueImmatriculation();
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(imm, CLUB_A), sysToken);
        String id = readJson(created).get("id").asText();

        // CLUB_ADMIN of CLUB_B attempts update → 403
        String adminB = mintToken(CLUB_B, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> upd = put("/api/v1/aircraft/" + id,
                updatePayload(uniqueImmatriculation()), adminB);
        assertThat(upd.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void clubAdminOfOwnerClub_canMutate_ownedAircraft() {
        String sysToken = mintToken(CLUB_A, "SYSTEM_ADMINISTRATOR");
        String imm = uniqueImmatriculation();
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(imm, CLUB_A), sysToken);
        String id = readJson(created).get("id").asText();

        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> upd = put("/api/v1/aircraft/" + id,
                updatePayload(uniqueImmatriculation()), adminA);
        assertThat(upd.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void clubAdmin_cannotMutate_nullOwnerAircraft() {
        String sysToken = mintToken(CLUB_A, "SYSTEM_ADMINISTRATOR");
        String imm = uniqueImmatriculation();
        // Charter aircraft: owner_club_id = null
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(imm, null), sysToken);
        String id = readJson(created).get("id").asText();

        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> upd = put("/api/v1/aircraft/" + id,
                updatePayload(uniqueImmatriculation()), adminA);
        assertThat(upd.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void clubAdmin_cannotRegisterAircraft_forOtherClub() {
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> res = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation(), CLUB_B), adminA);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void clubAdmin_cannotRegisterAircraft_withNullOwner() {
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> res = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation(), null), adminA);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void clubAdmin_canRegisterAircraft_forOwnClub() {
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> res = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation(), CLUB_A), adminA);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void anyAuthenticatedUser_canReadAnyAircraft() {
        // Sysadmin creates aircraft owned by CLUB_A
        String sysToken = mintToken(CLUB_A, "SYSTEM_ADMINISTRATOR");
        String imm = uniqueImmatriculation();
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(imm, CLUB_A), sysToken);
        String id = readJson(created).get("id").asText();

        // CLUB_ADMIN of CLUB_B can READ — full-fleet visibility by design
        String adminB = mintToken(CLUB_B, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> read = get("/api/v1/aircraft/" + id, adminB);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void onlySysAdmin_canTransferOwnership() {
        String sysToken = mintToken(CLUB_A, "SYSTEM_ADMINISTRATOR");
        String imm = uniqueImmatriculation();
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(imm, CLUB_A), sysToken);
        String id = readJson(created).get("id").asText();

        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> denied = post("/api/v1/aircraft/" + id + "/transfer-ownership",
                Map.of("newOwnerClubId", "clb-" + CLUB_B), adminA);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> allowed = post("/api/v1/aircraft/" + id + "/transfer-ownership",
                Map.of("newOwnerClubId", "clb-" + CLUB_B), sysToken);
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void flightOpsOfOwnerClub_canChangeState() {
        String sysToken = mintToken(CLUB_A, "SYSTEM_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation(), CLUB_A), sysToken);
        String id = readJson(created).get("id").asText();

        String opsA = mintToken(CLUB_A, "FLIGHT_OPS");
        ResponseEntity<String> res = post("/api/v1/aircraft/" + id + "/states",
                Map.of(
                        "aircraftStateId", SEED_AIRCRAFT_STATE_OK,
                        "validFrom", "2026-01-01T08:00:00Z"),
                opsA);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void flightOpsOfOtherClub_cannotChangeState() {
        String sysToken = mintToken(CLUB_A, "SYSTEM_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation(), CLUB_A), sysToken);
        String id = readJson(created).get("id").asText();

        String opsB = mintToken(CLUB_B, "FLIGHT_OPS");
        ResponseEntity<String> res = post("/api/v1/aircraft/" + id + "/states",
                Map.of(
                        "aircraftStateId", SEED_AIRCRAFT_STATE_OK,
                        "validFrom", "2026-01-01T08:00:00Z"),
                opsB);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ----- helpers -----

    private String mintToken(String clubId, String role) {
        Consumer<com.nimbusds.jwt.JWTClaimsSet.Builder> body = c -> c
                .claim("clubId", clubId)
                .claim("realm_access", Map.of("roles", List.of(role)));
        return jwts.mint(body);
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

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }

    @SuppressWarnings("unused")
    private static UUID asUuid(String s) {
        return UUID.fromString(s);
    }
}
