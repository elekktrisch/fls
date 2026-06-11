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
 * Authz matrix for the Aircraft REST surface under S-058 (reverts S-159):
 *
 * <ul>
 *   <li><strong>Reads are cross-tenant.</strong> Any authenticated user
 *       lists / picks / detail-reads any aircraft — the catalog is shared
 *       so a Club B user can pick Club A's tow plane on a Flight.</li>
 *   <li><strong>Writes are manager-gated</strong> via the
 *       {@code AircraftAccess} SpEL bean: CLUB_ADMINISTRATOR of
 *       {@code managing_club_id} for masterdata (update / soft-delete /
 *       transfer-ownership); CLUB_ADMINISTRATOR or FLIGHT_OPERATOR of
 *       {@code managing_club_id} for state + counter. Cross-club writes
 *       surface as 403, not 404.</li>
 *   <li><strong>SYSTEM_ADMINISTRATOR has fallback rights on masterdata</strong>
 *       writes (for cutover / cross-cutting admin) but is intentionally
 *       NOT a register-path role today — sysadmin lacks a clubId claim
 *       and the service can't infer a managing tenant.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class AircraftsAuthorizationIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLUB_A = "019e30c3-2c00-7001-8000-000000000001";
    private static final String CLUB_B = "019e30c3-2c00-7001-8000-0000000ab202";
    private static final String LANG_DE_UUID = "019e2e15-2c00-77d0-8000-0000000007d0";

    @Autowired TestRestTemplate rest;
    @Autowired JwtTestFixture jwts;
    @Autowired JdbcTemplate jdbc;

    /**
     * CLUB_A is seeded by V5 (walking skeleton). CLUB_B isn't, so the
     * cross-tenant matrix needs to ensure the FK target exists. Idempotent
     * via {@code ON CONFLICT DO NOTHING}.
     */
    @BeforeEach
    void cleanOwnerPersonFixtures() {
        // S-163 owner-person rows from prior runs (the User FK to Person means
        // users must drop before persons).
        jdbc.update("DELETE FROM t_user WHERE username LIKE 'aircraft-authz-it-%'");
        jdbc.update("DELETE FROM t_person WHERE firstname = 'AircraftAuthzIT'");
    }

    @BeforeEach
    void seedClubB() {
        UUID countryId = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        UUID clubStateId = jdbc.queryForObject("SELECT id FROM t_club_state LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO t_club (id, clubname, club_key, country_id, club_state_id, slug, public_registration_enabled)
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
    void clubAdminOfOtherClub_reads_crossTenantAircraft_but_cannot_write() {
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation()), adminA);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = readJson(created).get("id").asText();

        // CLUB_B's admin can read (cross-tenant catalog), but cannot write
        // (manager-club gated).
        String adminB = mintToken(CLUB_B, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> read = get("/api/v1/aircraft/" + id, adminB);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> upd = put("/api/v1/aircraft/" + id,
                updatePayload(uniqueImmatriculation()), adminB);
        assertThat(upd.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void latestCounter_isRedacted_forNonManagingClubReader() {
        // S-164: the inlined latestCounter on the detail projection reflects
        // the managing club's bookkeeping. It is surfaced to the managing
        // club's callers and redacted (null) for any other authenticated
        // reader — same managing-club predicate as edit (callerClubId ==
        // managingClubId). Reads stay cross-tenant otherwise (200, not 403).
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation()), adminA);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = readJson(created).get("id").asText();

        // Seed at least one operating counter so latestCounter is non-null
        // for the manager.
        ResponseEntity<String> counter = post("/api/v1/aircraft/" + id + "/counters",
                Map.of(
                        "atDateTime", "2026-01-01T10:00:00Z",
                        "flightOperatingCounterInSeconds", 3600),
                adminA);
        assertThat(counter.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Managing-club caller sees the counter (present + non-null).
        ResponseEntity<String> managerRead = get("/api/v1/aircraft/" + id, adminA);
        assertThat(managerRead.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode managerCounter = readJson(managerRead).get("latestCounter");
        assertThat(managerCounter).isNotNull();
        assertThat(managerCounter.isNull()).isFalse();

        // Different-club caller reads the same aircraft (cross-tenant catalog,
        // 200) but latestCounter is redacted — the field is absent / null in
        // the JSON (Jackson omits the null record component).
        String adminB = mintToken(CLUB_B, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> foreignRead = get("/api/v1/aircraft/" + id, adminB);
        assertThat(foreignRead.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode foreignCounter = readJson(foreignRead).get("latestCounter");
        assertThat(foreignCounter == null || foreignCounter.isNull()).isTrue();
    }

    @Test
    void ownerPerson_ofNonManagingClub_canEditAndDelete() {
        // S-163: net-new owner-person edit gate (legacy never admitted the
        // owner-person; operator chose to build it for J-1). A caller whose
        // linked Person (t_user.person_id, resolved from JWT sub) matches the
        // aircraft's aircraft_owner_person_id may edit + delete the aircraft
        // even though their club is NOT the managing club — admitted IN
        // ADDITION to the managing-club gate.
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation()), adminA);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = readJson(created).get("id").asText();
        // External id is `ac-<uuid>`; strip the prefix for the raw-SQL update.
        UUID aircraftId = UUID.fromString(id.substring("ac-".length()));

        // The owner-person and the CLUB_B user that links to it via the
        // JWT sub → User.person_id chain.
        UUID personId = UUID.randomUUID();
        UUID ownerSub = UUID.randomUUID();
        seedPerson(personId);
        seedUserLinkedToPerson(ownerSub, UUID.fromString(CLUB_B), personId);
        setAircraftOwnerPerson(aircraftId, personId);

        // CLUB_B (non-managing) but the JWT sub resolves to a User whose
        // person_id == the aircraft's owner person → admitted.
        String ownerToken = mintJitReady(ownerSub, CLUB_B, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> upd = put("/api/v1/aircraft/" + id,
                updatePayload(uniqueImmatriculation()), ownerToken);
        assertThat(upd.getStatusCode())
                .as("owner-person of a non-managing club may edit")
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> del = delete("/api/v1/aircraft/" + id, ownerToken);
        assertThat(del.getStatusCode())
                .as("owner-person of a non-managing club may delete")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void nonManagingCaller_withoutMatchingPersonLink_stillDenied() {
        // Regression guard for the managing-club gate: a non-managing caller
        // whose JWT sub resolves to a User whose person_id does NOT match the
        // aircraft's owner-person (here: no owner-person at all, and a
        // mismatched person link) still gets 403. The owner-person branch
        // must be a strict OR-add, never a widening of the existing gate.
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation()), adminA);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = readJson(created).get("id").asText();

        // A CLUB_B user with a person link that does NOT match the aircraft's
        // owner-person (the aircraft has no owner-person set at all).
        UUID otherPersonId = UUID.randomUUID();
        UUID otherSub = UUID.randomUUID();
        seedPerson(otherPersonId);
        seedUserLinkedToPerson(otherSub, UUID.fromString(CLUB_B), otherPersonId);

        String otherToken = mintJitReady(otherSub, CLUB_B, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> upd = put("/api/v1/aircraft/" + id,
                updatePayload(uniqueImmatriculation()), otherToken);
        assertThat(upd.getStatusCode())
                .as("non-managing caller with no matching person link is denied")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void clubAdminOfManagingClub_canMutate_ownAircraft() {
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation()), adminA);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = readJson(created).get("id").asText();

        ResponseEntity<String> upd = put("/api/v1/aircraft/" + id,
                updatePayload(uniqueImmatriculation()), adminA);
        assertThat(upd.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void sysAdmin_cannotRegister_lacksClubIdClaim() {
        // Register is intentionally CLUB_ADMINISTRATOR-only — sysadmin lacks
        // a clubId claim and the service can't infer the managing tenant.
        // A dedicated /admin/aircraft variant for sysadmin (with an explicit
        // managingClubId field) is deferred to a follow-up story.
        String sysToken = mintToken(null, "SYSTEM_ADMINISTRATOR");
        ResponseEntity<String> res = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation()), sysToken);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void sysAdmin_canMutate_existingAircraft() {
        // For pre-existing aircraft, SYSTEM_ADMIN is the universal fallback
        // on the AircraftAccess.canEdit / canOperate predicates — handles
        // the cutover / cross-cutting maintenance case.
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation()), adminA);
        String id = readJson(created).get("id").asText();

        String sysToken = mintToken(null, "SYSTEM_ADMINISTRATOR");
        ResponseEntity<String> upd = put("/api/v1/aircraft/" + id,
                updatePayload(uniqueImmatriculation()), sysToken);
        assertThat(upd.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void flightOperator_canChangeState_inOwnTenant() {
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation()), adminA);
        String id = readJson(created).get("id").asText();

        String opsA = mintToken(CLUB_A, "FLIGHT_OPERATOR");
        ResponseEntity<String> res = post("/api/v1/aircraft/" + id + "/states",
                Map.of(
                        "aircraftStateId", SEED_AIRCRAFT_STATE_OK,
                        "validFrom", "2026-01-01T08:00:00Z"),
                opsA);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void flightOperatorOfOtherClub_cannot_changeState_returns_403() {
        // Aircraft is cross-tenant on reads but writes are manager-club
        // gated — Club B's FLIGHT_OPERATOR can't change state on Club A's
        // aircraft. Surfaces as 403 (the row is readable; the write is
        // denied), not 404.
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation()), adminA);
        String id = readJson(created).get("id").asText();

        String opsB = mintToken(CLUB_B, "FLIGHT_OPERATOR");
        ResponseEntity<String> res = post("/api/v1/aircraft/" + id + "/states",
                Map.of(
                        "aircraftStateId", SEED_AIRCRAFT_STATE_OK,
                        "validFrom", "2026-01-01T08:00:00Z"),
                opsB);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void clubAdminOfOtherClub_cannot_listCounters_returns_403() {
        // Counter snapshots reflect the managing club's bookkeeping —
        // a foreign club's totals would be misleading (their flights live
        // in a different system). The list endpoint gates to manager-club
        // even though plain detail / state reads stay open.
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation()), adminA);
        String id = readJson(created).get("id").asText();

        String adminB = mintToken(CLUB_B, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> res = get("/api/v1/aircraft/" + id + "/counters", adminB);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void officeUser_cannotRecordCounter() {
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation()), adminA);
        String id = readJson(created).get("id").asText();

        String officeA = mintToken(CLUB_A, "OFFICE_USER");
        ResponseEntity<String> res = post("/api/v1/aircraft/" + id + "/counters",
                Map.of(
                        "atDateTime", "2026-01-01T10:00:00Z",
                        "flightOperatingCounterInSeconds", 3600),
                officeA);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void clubAdmin_canTransferOwnership_whenManagingClub() {
        // transferOwnership changes ownership metadata only; managing club
        // is unchanged. CLUB_ADMIN of the managing club is the gate.
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation()), adminA);
        String id = readJson(created).get("id").asText();

        ResponseEntity<String> res = post("/api/v1/aircraft/" + id + "/transfer-ownership",
                Map.of("newOwnerClubId", "clb-" + CLUB_B), adminA);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void flightOperator_cannotTransferOwnership() {
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation()), adminA);
        String id = readJson(created).get("id").asText();

        String opsA = mintToken(CLUB_A, "FLIGHT_OPERATOR");
        ResponseEntity<String> res = post("/api/v1/aircraft/" + id + "/transfer-ownership",
                Map.of("newOwnerClubId", "clb-" + CLUB_B), opsA);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ----- helpers -----

    private String mintToken(String clubId, String role) {
        Consumer<com.nimbusds.jwt.JWTClaimsSet.Builder> body = c -> {
            if (clubId != null) {
                c.claim("clubId", clubId);
            }
            c.claim("realm_access", Map.of("roles", List.of(role)));
        };
        return jwts.mint(body);
    }

    /**
     * Mint a token whose {@code sub} is a bare UUID (so the JWT→User→Person
     * chain resolves) carrying the given club + role. Distinct from
     * {@link #mintToken} (whose sub is a random {@code test-user-*} string the
     * User lookup never matches).
     */
    private String mintJitReady(UUID sub, String clubId, String role) {
        return jwts.mint(c -> {
            c.subject(sub.toString());
            if (clubId != null) {
                c.claim("clubId", clubId);
            }
            c.claim("realm_access", Map.of("roles", List.of(role)));
        });
    }

    private void seedPerson(UUID personId) {
        jdbc.update("INSERT INTO t_person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                personId.toString(), "AircraftAuthzIT", "OwnerPerson");
    }

    private void seedUserLinkedToPerson(UUID kcSub, UUID clubId, UUID personId) {
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, person_id,
                                    notification_email, language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?::uuid, ?, ?::uuid, ?::uuid)
                """,
                UUID.randomUUID().toString(), clubId.toString(),
                "aircraft-authz-it-" + kcSub, "Aircraft Authz IT Owner",
                personId.toString(), "owner@example.com",
                LANG_DE_UUID, kcSub.toString());
    }

    private void setAircraftOwnerPerson(UUID aircraftId, UUID personId) {
        jdbc.update("UPDATE t_aircraft SET aircraft_owner_person_id = ?::uuid WHERE id = ?::uuid",
                personId.toString(), aircraftId.toString());
    }

    private ResponseEntity<String> delete(String path, String token) {
        return rest.exchange(
                RequestEntity.delete(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build(),
                String.class);
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
}
