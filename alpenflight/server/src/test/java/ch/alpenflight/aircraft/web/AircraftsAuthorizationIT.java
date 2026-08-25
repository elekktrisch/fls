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

    @BeforeEach
    void deleteUsersBeforePersonsBecauseUserHasPersonFk() {
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
    void clubAdminOfOtherClubInSameDeployment_reads_crossTenantAircraft_but_cannot_write() {
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation()), adminA);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = readJson(created).get("id").asText();

        String adminB = mintToken(CLUB_B, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> read = get("/api/v1/aircraft/" + id, adminB);
        assertThat(read.getStatusCode())
                .as("both clubs sit in the operator Deployment, so the read stays open")
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> upd = put("/api/v1/aircraft/" + id,
                updatePayload(uniqueImmatriculation()), adminB);
        assertThat(upd.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void latestCounter_isRedacted_forNonManagingClubReader() {
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation()), adminA);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = readJson(created).get("id").asText();

        ResponseEntity<String> counter = post("/api/v1/aircraft/" + id + "/counters",
                Map.of(
                        "atDateTime", "2026-01-01T10:00:00Z",
                        "flightOperatingCounterInSeconds", 3600),
                adminA);
        assertThat(counter.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> managerRead = get("/api/v1/aircraft/" + id, adminA);
        assertThat(managerRead.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode managerCounter = readJson(managerRead).get("latestCounter");
        assertThat(managerCounter)
                .as("managing-club caller sees the counter it books")
                .isNotNull();
        assertThat(managerCounter.isNull()).isFalse();

        String adminB = mintToken(CLUB_B, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> foreignRead = get("/api/v1/aircraft/" + id, adminB);
        assertThat(foreignRead.getStatusCode())
                .as("reads stay cross-tenant inside one Deployment: the foreign reader still "
                        + "gets the aircraft")
                .isEqualTo(HttpStatus.OK);
        JsonNode foreignCounter = readJson(foreignRead).get("latestCounter");
        assertThat(foreignCounter == null || foreignCounter.isNull())
                .as("counter is redacted (absent or null) for any non-managing reader")
                .isTrue();
    }

    @Test
    void ownerPerson_ofNonManagingClub_canEditAndDelete() {
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation()), adminA);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = readJson(created).get("id").asText();
        UUID aircraftId = UUID.fromString(id.substring("ac-".length()));

        UUID personId = UUID.randomUUID();
        UUID ownerSub = UUID.randomUUID();
        seedPerson(personId);
        seedUserLinkedToPerson(ownerSub, UUID.fromString(CLUB_B), personId);
        setAircraftOwnerPerson(aircraftId, personId);

        String ownerToken = mintTokenWhoseSubjectResolvesToUser(
                ownerSub, CLUB_B, "CLUB_ADMINISTRATOR");
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
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation()), adminA);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = readJson(created).get("id").asText();

        UUID nonOwnerPersonId = UUID.randomUUID();
        UUID nonOwnerSub = UUID.randomUUID();
        seedPerson(nonOwnerPersonId);
        seedUserLinkedToPerson(nonOwnerSub, UUID.fromString(CLUB_B), nonOwnerPersonId);

        String nonOwnerToken = mintTokenWhoseSubjectResolvesToUser(
                nonOwnerSub, CLUB_B, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> upd = put("/api/v1/aircraft/" + id,
                updatePayload(uniqueImmatriculation()), nonOwnerToken);
        assertThat(upd.getStatusCode())
                .as("the owner-person branch is a strict OR-add, never a widening: a non-managing "
                        + "caller whose person link is not the owner-person is still denied")
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
        String sysToken = mintToken(null, "SYSTEM_ADMINISTRATOR");
        ResponseEntity<String> res = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation()), sysToken);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void sysAdmin_canMutate_existingAircraft() {
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
        assertThat(res.getStatusCode())
                .as("the row is readable cross-tenant, so a denied write is 403, not a hiding 404")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void clubAdminOfOtherClub_cannot_listCounters_returns_403() {
        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> created = post("/api/v1/aircraft",
                createPayload(uniqueImmatriculation()), adminA);
        String id = readJson(created).get("id").asText();

        String adminB = mintToken(CLUB_B, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> res = get("/api/v1/aircraft/" + id + "/counters", adminB);
        assertThat(res.getStatusCode())
                .as("counter listing is managing-club only even though detail reads stay open")
                .isEqualTo(HttpStatus.FORBIDDEN);
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


    private String mintToken(String clubId, String role) {
        Consumer<com.nimbusds.jwt.JWTClaimsSet.Builder> body = c -> {
            if (clubId != null) {
                c.claim("clubId", clubId);
            }
            c.claim("realm_access", Map.of("roles", List.of(role)));
        };
        return jwts.mint(body);
    }

    private String mintTokenWhoseSubjectResolvesToUser(UUID sub, String clubId, String role) {
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
