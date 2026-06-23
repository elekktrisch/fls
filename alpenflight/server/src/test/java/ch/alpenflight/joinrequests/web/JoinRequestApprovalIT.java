package ch.alpenflight.joinrequests.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import ch.alpenflight.users.domain.UserDirectoryException;
import ch.alpenflight.users.domain.UserDirectoryPort;
import ch.alpenflight.users.domain.UserDirectoryPort.RealmRoleRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.uuid.UuidCreator;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Cross-system approve/deny integration test (S-178, T-06). The Keycloak adapter
 * is mocked ({@link UserDirectoryPort}) so the test can assert the {@code clubId}
 * attribute write happened and can inject a directory failure to prove the
 * partial-failure (half-join) defence.
 *
 * <p>Approve, per the contract, is one transaction that: writes the KC
 * {@code clubId} attribute, creates a {@code t_user}, auto-creates (or links) a
 * Person + PersonClub with default roles, applies {@code roles[]}, moves the
 * aggregate PENDING → APPROVED, and audits. The ordering puts the idempotent KC
 * attribute write FIRST so a rolled-back DB transaction strands nothing
 * load-bearing (a clubId attribute with no t_user is inert; the request stays
 * PENDING and re-approve re-runs).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class JoinRequestApprovalIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;
    @MockitoBean UserDirectoryPort directory;

    private UUID clubA;
    private UUID clubB;
    private String codeA;
    private UUID adminSubA;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_JRA_", "IT_JRA_");
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
        codeA = clubs.findActiveById(clubA).map(Club::getJoinCode).orElseThrow();
        adminSubA = UuidCreator.getTimeOrderedEpoch();
        seedUser(adminSubA, clubA, "admin-a");
        // KC role-resolution stubs — the directory is mocked, so a grant must
        // resolve realm-role refs by name.
        when(directory.findRealmRolesByName(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.Set<String> names = (java.util.Set<String>) inv.getArgument(0);
            return names.stream().map(n -> new RealmRoleRef(UUID.randomUUID().toString(), n, null)).toList();
        });
    }

    // ---- approve happy (auto-Person) ----

    @Test
    void approve_happy_writesKcAttr_createsUser_autoPerson_andApproves() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        String reqId = filePending(sub, codeA);

        ResponseEntity<String> res = approve(adminToken(clubA, adminSubA), reqId,
                List.of("PILOT"), null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(res).get("status").asText()).isEqualTo("APPROVED");

        verify(directory).writeClubIdAttribute(sub, clubA);
        verify(directory).grantRealmRoles(eq(sub), anyList());

        // t_user created for the sub, in club A.
        UUID userClub = jdbc.queryForObject(
                "SELECT club_id FROM t_user WHERE keycloak_sub = ?::uuid AND deleted_on IS NULL",
                UUID.class, sub.toString());
        assertThat(userClub).isEqualTo(clubA);

        // Auto-Person + PersonClub created in club A and bound to the user.
        UUID personId = jdbc.queryForObject(
                "SELECT person_id FROM t_user WHERE keycloak_sub = ?::uuid", UUID.class, sub.toString());
        assertThat(personId).isNotNull();
        Integer pcCount = jdbc.queryForObject(
                "SELECT count(*) FROM t_person_club WHERE person_id = ?::uuid AND club_id = ?::uuid "
                        + "AND deleted_on IS NULL",
                Integer.class, personId.toString(), clubA.toString());
        assertThat(pcCount).isEqualTo(1);

        // Aggregate row: decided_on + decided_by_user_id stamped.
        UUID decidedBy = jdbc.queryForObject(
                "SELECT decided_by_user_id FROM t_join_request WHERE id = ?::uuid", UUID.class, reqId);
        assertThat(decidedBy).isNotNull();
    }

    // ---- approve with personId (link) ----

    @Test
    void approve_withPersonId_linksExistingPerson() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        String reqId = filePending(sub, codeA);
        UUID personId = seedPersonInClub(clubA);

        ResponseEntity<String> res = approve(adminToken(clubA, adminSubA), reqId,
                List.of("PILOT"), personId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID linked = jdbc.queryForObject(
                "SELECT person_id FROM t_user WHERE keycloak_sub = ?::uuid", UUID.class, sub.toString());
        assertThat(linked).isEqualTo(personId);
    }

    @Test
    void approve_withCrossTenantPersonId_is_409() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        String reqId = filePending(sub, codeA);
        UUID personInB = seedPersonInClub(clubB);

        ResponseEntity<String> res = approve(adminToken(clubA, adminSubA), reqId,
                List.of("PILOT"), personInB);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // No t_user materialized — the cross-tenant link aborted the transaction.
        Integer users = jdbc.queryForObject(
                "SELECT count(*) FROM t_user WHERE keycloak_sub = ?::uuid", Integer.class, sub.toString());
        assertThat(users).isZero();
    }

    // ---- re-approve idempotency ----

    @Test
    void reApprove_alreadyApproved_is_409_noDoubleJoin() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        String reqId = filePending(sub, codeA);
        assertThat(approve(adminToken(clubA, adminSubA), reqId, List.of("PILOT"), null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = approve(adminToken(clubA, adminSubA), reqId,
                List.of("PILOT"), null);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Exactly one t_user — no double-join.
        Integer users = jdbc.queryForObject(
                "SELECT count(*) FROM t_user WHERE keycloak_sub = ?::uuid AND deleted_on IS NULL",
                Integer.class, sub.toString());
        assertThat(users).isEqualTo(1);
    }

    // ---- the integration-risk test: DB failure after KC write strands no half-join ----

    @Test
    void approve_dbFailsAfterKcWrite_leavesNoHalfJoin_andRequestStaysPending() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        String reqId = filePending(sub, codeA);

        // Force the in-transaction KC role grant to throw AFTER the idempotent
        // attribute write — the DB transaction must roll back, stranding no
        // t_user / Person and leaving the request PENDING (re-approvable).
        doThrow(new UserDirectoryException("simulated KC grant failure"))
                .when(directory).grantRealmRoles(any(), anyList());

        ResponseEntity<String> res = approve(adminToken(clubA, adminSubA), reqId,
                List.of("PILOT"), null);
        assertThat(res.getStatusCode().is5xxServerError())
                .as("KC failure mid-transaction rolls everything back").isTrue();

        Integer users = jdbc.queryForObject(
                "SELECT count(*) FROM t_user WHERE keycloak_sub = ?::uuid", Integer.class, sub.toString());
        assertThat(users).as("no stranded t_user").isZero();
        String status = jdbc.queryForObject(
                "SELECT status FROM t_join_request WHERE id = ?::uuid", String.class, reqId);
        assertThat(status).as("request stays PENDING — re-approvable").isEqualTo("PENDING");
    }

    // ---- deny ----

    @Test
    void deny_withReason_movesToDenied() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        String reqId = filePending(sub, codeA);

        ResponseEntity<String> res = deny(adminToken(clubA, adminSubA), reqId, "not this year");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(res).get("status").asText()).isEqualTo("DENIED");
        assertThat(readJson(res).get("decisionReason").asText()).isEqualTo("not this year");

        // No t_user / no KC attribute write on deny.
        Integer users = jdbc.queryForObject(
                "SELECT count(*) FROM t_user WHERE keycloak_sub = ?::uuid", Integer.class, sub.toString());
        assertThat(users).isZero();
        verify(directory, never()).writeClubIdAttribute(eq(sub), any());
    }

    // ---- authz ----

    @Test
    void approve_nonAdmin_is_403() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        String reqId = filePending(sub, codeA);
        ResponseEntity<String> res = approve(pilotToken(UuidCreator.getTimeOrderedEpoch()), reqId,
                List.of("PILOT"), null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void approve_crossTenantRequest_is_404() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        String reqId = filePending(sub, codeA);
        // Admin of club B cannot see / approve club A's request.
        UUID adminSubB = UuidCreator.getTimeOrderedEpoch();
        seedUser(adminSubB, clubB, "admin-b");
        ResponseEntity<String> res = approve(adminToken(clubB, adminSubB), reqId,
                List.of("PILOT"), null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- helpers ----

    private String filePending(UUID sub, String code) {
        ResponseEntity<String> res = submit(pilotToken(sub), code, "let me in");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return readJson(res).get("id").asText();
    }

    private String pilotToken(UUID sub) {
        return jwts.mint(c -> c
                .subject(sub.toString())
                .claim("email", "pilot-" + sub + "@example.com")
                .claim("given_name", "Test")
                .claim("family_name", "Pilot")
                .claim("preferred_username", "pilot-" + sub)
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));
    }

    private String adminToken(UUID clubId, UUID sub) {
        return jwts.mint(c -> c
                .subject(sub.toString())
                .claim("clubId", clubId.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    private void seedUser(UUID sub, UUID clubId, String username) {
        UUID languageId = jdbc.queryForObject("SELECT id FROM t_language LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO t_user (id, club_id, username, friendly_name, "
                        + "notification_email, language_id, keycloak_sub, created_on, modified_on) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid, now(), now())",
                UuidCreator.getTimeOrderedEpoch().toString(), clubId.toString(),
                username + "-" + sub, "Seed " + username, username + "@example.com",
                languageId.toString(), sub.toString());
    }

    private UUID seedPersonInClub(UUID clubId) {
        UUID personId = UuidCreator.getTimeOrderedEpoch();
        jdbc.update("INSERT INTO t_person (id, firstname, lastname) VALUES (?::uuid, 'Existing', 'Person')",
                personId.toString());
        jdbc.update("INSERT INTO t_person_club (id, person_id, club_id, is_active) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid, true)",
                UuidCreator.getTimeOrderedEpoch().toString(), personId.toString(), clubId.toString());
        return personId;
    }

    private ResponseEntity<String> submit(String token, String joinCode, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("joinCode", joinCode);
        if (note != null) {
            body.put("note", note);
        }
        return post(token, "/api/v1/join-requests", body);
    }

    private ResponseEntity<String> approve(String token, String reqId, List<String> roles, UUID personId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roles", roles);
        if (personId != null) {
            body.put("personId", personId.toString());
        }
        return post(token, "/api/v1/join-requests/" + reqId + "/approve", body);
    }

    private ResponseEntity<String> deny(String token, String reqId, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (reason != null) {
            body.put("reason", reason);
        }
        return post(token, "/api/v1/join-requests/" + reqId + "/deny", body);
    }

    private ResponseEntity<String> post(String token, String path, Map<String, Object> body) {
        RequestEntity.BodyBuilder b = RequestEntity.post(URI.create(path))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        return rest.exchange(body == null ? b.build() : b.body(body), String.class);
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse: " + res.getBody(), e);
        }
    }
}
