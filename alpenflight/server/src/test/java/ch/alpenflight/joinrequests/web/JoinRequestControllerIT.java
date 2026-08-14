package ch.alpenflight.joinrequests.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
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

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class JoinRequestControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID clubA;
    private UUID clubB;
    private String codeA;
    private String codeB;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_JRC_", "IT_JRC_");
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
        Club a = clubs.findActiveById(clubA).orElseThrow();
        a.setPublicDisplay("Zurich", "https://example.com/clubA-logo.png");
        clubs.save(a);
        codeA = a.getJoinCode();
        codeB = clubs.findActiveById(clubB).map(Club::getJoinCode).orElseThrow();
    }


    @Test
    void submit_validCode_returns_201_with_pending_request() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        ResponseEntity<String> res = submit(pilotToken(sub), codeA, "let me in");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = readJson(res);
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(body.get("clubId").asText()).isEqualTo(clubA.toString());
        assertThat(body.get("note").asText()).isEqualTo("let me in");
        assertThat(body.get("city").asText()).isEqualTo("Zurich");
        assertThat(body.get("logoUrl").asText()).isEqualTo("https://example.com/clubA-logo.png");
        URI loc = res.getHeaders().getLocation();
        assertThat(loc).isNotNull();
        assertThat(loc.getPath()).isEqualTo("/api/v1/join-requests/" + body.get("id").asText());
    }

    @Test
    void submit_unknownCode_returns_404() {
        ResponseEntity<String> res = submit(pilotToken(UuidCreator.getTimeOrderedEpoch()),
                "NOPENOPE", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void submit_callerAlreadyHasUser_returns_409() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        seedUser(sub, clubB);
        ResponseEntity<String> res = submit(pilotToken(sub), codeA, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void submit_secondPendingForSamePair_returns_409() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        assertThat(submit(pilotToken(sub), codeA, null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(submit(pilotToken(sub), codeA, null).getStatusCode())
                .as("ux_join_request_alive — one open request per (sub, club)")
                .isEqualTo(HttpStatus.CONFLICT);
    }


    @Test
    void withdraw_ownSub_returns_200_withdrawn() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        String id = readJson(submit(pilotToken(sub), codeA, null)).get("id").asText();
        ResponseEntity<String> res = post(pilotToken(sub),
                "/api/v1/join-requests/" + id + "/withdraw", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(res).get("status").asText()).isEqualTo("WITHDRAWN");
    }

    @Test
    void withdraw_otherSub_is_forbidden() {
        UUID owner = UuidCreator.getTimeOrderedEpoch();
        UUID stranger = UuidCreator.getTimeOrderedEpoch();
        String id = readJson(submit(pilotToken(owner), codeA, null)).get("id").asText();
        ResponseEntity<String> res = post(pilotToken(stranger),
                "/api/v1/join-requests/" + id + "/withdraw", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }


    @Test
    void me_returns_204_when_none() {
        ResponseEntity<String> res = get(pilotToken(UuidCreator.getTimeOrderedEpoch()),
                "/api/v1/me/join-request");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void me_returns_200_with_latest_request() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        String token = pilotToken(sub);
        submit(token, codeA, "hi");
        ResponseEntity<String> res = get(token, "/api/v1/me/join-request");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.get("clubId").asText()).isEqualTo(clubA.toString());
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void me_returns_public_club_display_for_owner() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        String token = pilotToken(sub);
        submit(token, codeA, "hi");
        JsonNode body = readJson(get(token, "/api/v1/me/join-request"));
        assertThat(body.get("clubName").asText())
                .isEqualTo(clubs.findActiveById(clubA).orElseThrow().getClubname());
        assertThat(body.get("city").asText()).isEqualTo("Zurich");
        assertThat(body.get("logoUrl").asText()).isEqualTo("https://example.com/clubA-logo.png");
    }


    @Test
    void adminPendingList_isTenantScoped_and_excludes_other_clubs() {
        UUID subInA = UuidCreator.getTimeOrderedEpoch();
        UUID subInB = UuidCreator.getTimeOrderedEpoch();
        submit(pilotToken(subInA), codeA, null);
        submit(pilotToken(subInB), codeB, null);

        ResponseEntity<String> res = get(adminToken(clubA),
                "/api/v1/join-requests?status=pending");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode list = readJson(res);
        assertThat(list.isArray()).isTrue();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("clubId").asText()).isEqualTo(clubA.toString());
    }

    @Test
    void adminPendingList_nonAdmin_is_403() {
        ResponseEntity<String> res = get(pilotToken(UuidCreator.getTimeOrderedEpoch()),
                "/api/v1/join-requests?status=pending");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
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

    private String adminToken(UUID clubId) {
        return jwts.mint(c -> c
                .subject(UuidCreator.getTimeOrderedEpoch().toString())
                .claim("clubId", clubId.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    private void seedUser(UUID sub, UUID clubId) {
        UUID languageId = jdbc.queryForObject("SELECT id FROM t_language LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO t_user (id, club_id, username, friendly_name, "
                        + "notification_email, language_id, keycloak_sub, created_on, modified_on) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid, now(), now())",
                UuidCreator.getTimeOrderedEpoch().toString(), clubId.toString(),
                "existing-" + sub, "Existing User", "existing@example.com",
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

    private ResponseEntity<String> get(String token, String path) {
        return rest.exchange(RequestEntity.get(URI.create(path))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).build(), String.class);
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
