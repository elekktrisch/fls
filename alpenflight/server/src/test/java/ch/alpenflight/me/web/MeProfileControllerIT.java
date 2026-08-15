package ch.alpenflight.me.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class MeProfileControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID CLUB_UUID =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final UUID LANG_DE_UUID =
            UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");
    private static final UUID LANG_EN_UUID =
            UUID.fromString("019e2e15-2c00-77d3-8000-0000000007d3");

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    @BeforeEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM t_user WHERE username LIKE 'meprof-it-%'");
    }

    @Test
    void patchProfile_updatesCallersOwnFields_persistsAndPreservesRemarks() {
        UUID kcSub = UUID.randomUUID();
        UUID userId = seedUser(kcSub, "meprof-it-a", "Old Name",
                "old@example.com", "+41 11 111", "admin-only note", LANG_DE_UUID);

        String token = pilotToken(kcSub);
        ResponseEntity<String> res = patch("/api/v1/me/profile", Map.of(
                "friendlyName", "New Name",
                "notificationEmail", "new@example.com",
                "phoneNumber", "+41 22 222",
                "languageId", LANG_EN_UUID.toString()), token);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.get("email").asText()).isEqualTo("new@example.com");
        assertThat(body.get("username").asText()).isEqualTo("meprof-it-a");
        assertThat(body.get("friendlyName").asText()).isEqualTo("New Name");
        assertThat(body.get("phoneNumber").asText()).isEqualTo("+41 22 222");
        assertThat(body.get("languageId").asText()).isEqualTo(LANG_EN_UUID.toString());
        assertThat(body.get("languageCode").asText())
                .as("the PATCH response reuses the /me projection, which carries the Account "
                        + "self-fields (languageCode = BCP-47 of the chosen language) so the "
                        + "SPA form reflects the round-trip without a second endpoint")
                .isEqualTo("en");

        JsonNode reread = readJson(get("/api/v1/me", token));
        assertThat(reread.get("email").asText())
                .as("a fresh GET /me reflects the persisted change")
                .isEqualTo("new@example.com");
        assertThat(reread.get("friendlyName").asText()).isEqualTo("New Name");
        assertThat(reread.get("languageId").asText()).isEqualTo(LANG_EN_UUID.toString());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT friendly_name, notification_email, phone_number, language_id, remarks "
                        + "FROM t_user WHERE id = ?::uuid", userId.toString());
        assertThat(row.get("friendly_name")).isEqualTo("New Name");
        assertThat(row.get("notification_email")).isEqualTo("new@example.com");
        assertThat(row.get("phone_number")).isEqualTo("+41 22 222");
        assertThat(row.get("language_id")).hasToString(LANG_EN_UUID.toString());
        assertThat(row.get("remarks"))
                .as("remarks is admin-only and must survive a self-edit untouched")
                .isEqualTo("admin-only note");
    }

    @Test
    void patchProfile_resolvesCallerFromJwt_neverTouchesAnotherPrincipalsRow() {
        UUID subA = UUID.randomUUID();
        UUID subB = UUID.randomUUID();
        UUID userA = seedUser(subA, "meprof-it-self", "A Name",
                "a@example.com", "+41 0 a", "remarks-a", LANG_DE_UUID);
        UUID userB = seedUser(subB, "meprof-it-other", "B Name",
                "b@example.com", "+41 0 b", "remarks-b", LANG_DE_UUID);

        ResponseEntity<String> res = patch("/api/v1/me/profile", Map.of(
                "friendlyName", "A Edited",
                "notificationEmail", "a-edited@example.com",
                "languageId", LANG_EN_UUID.toString()), pilotToken(subA));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> rowA = jdbc.queryForMap(
                "SELECT friendly_name, notification_email FROM t_user WHERE id = ?::uuid",
                userA.toString());
        assertThat(rowA.get("notification_email")).isEqualTo("a-edited@example.com");

        Map<String, Object> rowB = jdbc.queryForMap(
                "SELECT friendly_name, notification_email FROM t_user WHERE id = ?::uuid",
                userB.toString());
        assertThat(rowB.get("friendly_name"))
                .as("principal B's row is untouched — the endpoint carries no :id, so the "
                        + "row to edit can only ever be the caller's own")
                .isEqualTo("B Name");
        assertThat(rowB.get("notification_email")).isEqualTo("b@example.com");
    }

    @Test
    void patchProfile_rejectsBlankFriendlyNameAndMalformedEmail_with400() {
        UUID kcSub = UUID.randomUUID();
        seedUser(kcSub, "meprof-it-val", "Val Name",
                "val@example.com", null, null, LANG_DE_UUID);
        String token = pilotToken(kcSub);

        ResponseEntity<String> blank = patch("/api/v1/me/profile", Map.of(
                "friendlyName", "   ",
                "notificationEmail", "val@example.com",
                "languageId", LANG_DE_UUID.toString()), token);
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> badEmail = patch("/api/v1/me/profile", Map.of(
                "friendlyName", "Val Name",
                "notificationEmail", "not-an-email",
                "languageId", LANG_DE_UUID.toString()), token);
        assertThat(badEmail.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT friendly_name, notification_email FROM t_user WHERE keycloak_sub = ?::uuid",
                kcSub.toString());
        assertThat(row.get("friendly_name"))
                .as("nothing persisted from either rejected request")
                .isEqualTo("Val Name");
        assertThat(row.get("notification_email")).isEqualTo("val@example.com");
    }

    private UUID seedUser(UUID kcSub, String username, String friendlyName,
                          String notificationEmail, String phoneNumber,
                          String remarks, UUID languageId) {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, person_id,
                                    notification_email, phone_number, remarks, language_id,
                                    keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, NULL, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                userId.toString(), CLUB_UUID.toString(), username, friendlyName,
                notificationEmail, phoneNumber, remarks, languageId.toString(),
                kcSub.toString());
        return userId;
    }

    private String pilotToken(UUID kcSub) {
        return jwts.mint(c -> c
                .subject(kcSub.toString())
                .claim("clubId", CLUB_UUID.toString())
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));
    }

    private ResponseEntity<String> patch(String path, Object body, String token) {
        return rest.exchange(
                RequestEntity.method(HttpMethod.PATCH, URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .body(body),
                String.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(
                RequestEntity.get(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build(),
                String.class);
    }

    private JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse JSON: " + res.getBody(), e);
        }
    }
}
