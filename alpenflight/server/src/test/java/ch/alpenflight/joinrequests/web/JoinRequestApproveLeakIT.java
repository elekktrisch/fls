package ch.alpenflight.joinrequests.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
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
import java.util.concurrent.ConcurrentHashMap;
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

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class JoinRequestApproveLeakIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;
    @MockitoBean UserDirectoryPort directory;

    private final Map<UUID, UUID> clubIdAttributeTheRealmMapperWouldProject =
            new ConcurrentHashMap<>();

    private UUID clubA;
    private String codeA;
    private UUID adminSubA;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_JRL_", "IT_JRL_");
        fixture.seed();
        clubA = fixture.clubA();
        codeA = clubs.findActiveById(clubA).map(Club::getJoinCode).orElseThrow();
        adminSubA = UuidCreator.getTimeOrderedEpoch();
        seedUser(adminSubA, clubA, "admin-a");
        clubIdAttributeTheRealmMapperWouldProject.clear();
        reset(directory);
        stubDirectoryToRecordClubIdAttributeWritesAndClears();
    }

    @Test
    void halfFailedApprove_strandsNoClubIdAttribute_pilotStaysTenantLess_andRequestStaysActionable() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        String reqId = filePending(sub, codeA);

        doThrow(new UserDirectoryException("simulated KC grant failure"))
                .when(directory).grantRealmRoles(any(), anyList());
        ResponseEntity<String> failed = approve(adminToken(clubA, adminSubA), reqId,
                List.of("PILOT"), null);
        assertThat(failed.getStatusCode().is5xxServerError())
                .as("KC failure mid-transaction rolls everything back").isTrue();

        assertThat(clubIdAttributeTheRealmMapperWouldProject.get(sub))
                .as("a rolled-back approve must strand no clubId attribute").isNull();

        ResponseEntity<String> meAsProjected =
                get(projectedPilotToken(sub), "/api/v1/me/join-request");
        assertThat(meAsProjected.getStatusCode().is2xxSuccessful())
                .as("the projected token is accepted (authenticated)").isTrue();
        Integer materialized = jdbc.queryForObject(
                "SELECT count(*) FROM t_user WHERE keycloak_sub = ?::uuid AND deleted_on IS NULL",
                Integer.class, sub.toString());
        assertThat(materialized).as("no t_user materializes for the half-joined pilot").isZero();

        ResponseEntity<String> pendingAsProjected =
                get(projectedAdminToken(sub), "/api/v1/join-requests?status=pending");
        assertThat(pendingAsProjected.getStatusCode().is2xxSuccessful()
                ? readArray(pendingAsProjected).isEmpty()
                : true)
                .as("tenant-less projected principal reads no club-A pending rows").isTrue();

        reset(directory);
        stubDirectoryToRecordClubIdAttributeWritesAndClears();
        ResponseEntity<String> realApprove = approve(adminToken(clubA, adminSubA), reqId,
                List.of("PILOT"), null);
        assertThat(realApprove.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(realApprove).get("status").asText()).isEqualTo("APPROVED");
        UUID userClub = jdbc.queryForObject(
                "SELECT club_id FROM t_user WHERE keycloak_sub = ?::uuid AND deleted_on IS NULL",
                UUID.class, sub.toString());
        assertThat(userClub).as("the recovered approve lands the pilot in club A").isEqualTo(clubA);
    }


    private void stubDirectoryToRecordClubIdAttributeWritesAndClears() {
        when(directory.findRealmRolesByName(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.Set<String> names = (java.util.Set<String>) inv.getArgument(0);
            return names.stream().map(n -> new RealmRoleRef(UUID.randomUUID().toString(), n, null)).toList();
        });
        org.mockito.Mockito.doAnswer(inv -> {
            clubIdAttributeTheRealmMapperWouldProject.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(directory).writeClubIdAttribute(any(), any());
        org.mockito.Mockito.doAnswer(inv -> {
            clubIdAttributeTheRealmMapperWouldProject.remove(inv.getArgument(0));
            return null;
        }).when(directory).clearClubIdAttribute(any());
    }

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

    private String projectedPilotToken(UUID sub) {
        UUID recordedClubIdAttribute = clubIdAttributeTheRealmMapperWouldProject.get(sub);
        return jwts.mint(c -> {
            c.subject(sub.toString())
                    .claim("email", "pilot-" + sub + "@example.com")
                    .claim("given_name", "Test")
                    .claim("preferred_username", "pilot-" + sub)
                    .claim("realm_access", Map.of("roles", List.of("PILOT")));
            if (recordedClubIdAttribute != null) {
                c.claim("clubId", recordedClubIdAttribute.toString());
            }
        });
    }

    private String projectedAdminToken(UUID sub) {
        UUID recordedClubIdAttribute = clubIdAttributeTheRealmMapperWouldProject.get(sub);
        return jwts.mint(c -> {
            c.subject(sub.toString())
                    .claim("email", "pilot-" + sub + "@example.com")
                    .claim("given_name", "Test")
                    .claim("preferred_username", "pilot-" + sub)
                    .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR")));
            if (recordedClubIdAttribute != null) {
                c.claim("clubId", recordedClubIdAttribute.toString());
            }
        });
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

    private ResponseEntity<String> post(String token, String path, Map<String, Object> body) {
        RequestEntity.BodyBuilder b = RequestEntity.post(URI.create(path))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        return rest.exchange(b.body(body), String.class);
    }

    private ResponseEntity<String> get(String token, String path) {
        RequestEntity<Void> req = RequestEntity.get(URI.create(path))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        return rest.exchange(req, String.class);
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse: " + res.getBody(), e);
        }
    }

    private static List<JsonNode> readArray(ResponseEntity<String> res) {
        JsonNode root = readJson(res);
        java.util.ArrayList<JsonNode> out = new java.util.ArrayList<>();
        if (root.isArray()) {
            root.forEach(out::add);
        }
        return out;
    }
}
