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
class MePersonControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID CLUB_UUID =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final UUID LANG_DE_UUID =
            UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");
    private static final String TEARDOWN_MARKER_COMPANY_NAME = "MePersonIT";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    @BeforeEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM t_user WHERE username LIKE 'meperson-it-%'");
        jdbc.update("DELETE FROM t_person WHERE company_name = ?", TEARDOWN_MARKER_COMPANY_NAME);
    }

    @Test
    void getPerson_returnsCallersOwnContactAndReadonlyNames() {
        UUID kcSub = UUID.randomUUID();
        UUID personId = seedPerson("Ada", "Lovelace", "M");
        jdbc.update("UPDATE t_person SET address_line1 = '12 Analytical Ave', zip = '8000', "
                        + "city = 'Zurich', region = 'ZH', private_phone = '+41 44 111 22 33', "
                        + "business_phone = '+41 44 999 88 77', email_private = 'ada@example.com', "
                        + "prefer_mail_to_business_mail = true, birthday = DATE '1815-12-10' "
                        + "WHERE id = ?::uuid", personId.toString());
        seedUser(kcSub, "meperson-it-get", personId);

        ResponseEntity<String> res = get("/api/v1/me/person", pilotToken(kcSub));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode body = parse(res.getBody());
        assertThat(body.get("addressLine1").asText()).isEqualTo("12 Analytical Ave");
        assertThat(body.get("zip").asText()).isEqualTo("8000");
        assertThat(body.get("city").asText()).isEqualTo("Zurich");
        assertThat(body.get("region").asText()).isEqualTo("ZH");
        assertThat(body.get("privatePhone").asText()).isEqualTo("+41 44 111 22 33");
        assertThat(body.get("businessPhone").asText()).isEqualTo("+41 44 999 88 77");
        assertThat(body.get("emailPrivate").asText()).isEqualTo("ada@example.com");
        assertThat(body.get("preferMailToBusinessMail").asBoolean()).isTrue();
        assertThat(body.get("birthday").asText()).isEqualTo("1815-12-10");
        assertThat(body.get("firstName").asText())
                .as("read-only name fields ride along for display")
                .isEqualTo("Ada");
        assertThat(body.get("lastName").asText()).isEqualTo("Lovelace");
        assertThat(body.get("midName").asText()).isEqualTo("M");
    }

    @Test
    void getPerson_resolvesCallerFromJwt_neverReadsAnotherPrincipalsPerson() {
        UUID subA = UUID.randomUUID();
        UUID subB = UUID.randomUUID();
        UUID personA = seedPerson("Ada", "Lovelace", null);
        UUID personB = seedPerson("Grace", "Hopper", null);
        jdbc.update("UPDATE t_person SET city = 'A-City' WHERE id = ?::uuid", personA.toString());
        jdbc.update("UPDATE t_person SET city = 'B-City' WHERE id = ?::uuid", personB.toString());
        seedUser(subA, "meperson-it-get-self", personA);
        seedUser(subB, "meperson-it-get-other", personB);

        JsonNode body = parse(get("/api/v1/me/person", pilotToken(subA)).getBody());
        assertThat(body.get("firstName").asText())
                .as("A reads A's own contact — B's is unreachable through this surface")
                .isEqualTo("Ada");
        assertThat(body.get("city").asText()).isEqualTo("A-City");
    }

    @Test
    void getPerson_callerWithNoLinkedPerson_returns409() {
        UUID kcSub = UUID.randomUUID();
        seedUser(kcSub, "meperson-it-get-noperson", null);

        ResponseEntity<String> res = get("/api/v1/me/person", pilotToken(kcSub));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void patchPerson_updatesCallersOwnContact_persistsAndLeavesNamesUnchanged() {
        UUID kcSub = UUID.randomUUID();
        UUID personId = seedPerson("Ada", "Lovelace", "M");
        seedUser(kcSub, "meperson-it-a", personId);

        String token = pilotToken(kcSub);
        ResponseEntity<String> res = patch("/api/v1/me/person", Map.of(
                "addressLine1", "12 Analytical Ave",
                "zip", "8000",
                "city", "Zurich",
                "region", "ZH",
                "privatePhone", "+41 44 111 22 33",
                "businessPhone", "+41 44 999 88 77",
                "emailPrivate", "ada.private@example.com",
                "emailBusiness", "ada.biz@example.com",
                "preferMailToBusinessMail", true), token);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT firstname, lastname, midname, address_line1, zip, city, region, "
                        + "private_phone, business_phone, email_private, email_business, "
                        + "prefer_mail_to_business_mail "
                        + "FROM t_person WHERE id = ?::uuid", personId.toString());
        assertThat(row.get("address_line1")).isEqualTo("12 Analytical Ave");
        assertThat(row.get("zip")).isEqualTo("8000");
        assertThat(row.get("city")).isEqualTo("Zurich");
        assertThat(row.get("region")).isEqualTo("ZH");
        assertThat(row.get("private_phone")).isEqualTo("+41 44 111 22 33");
        assertThat(row.get("business_phone")).isEqualTo("+41 44 999 88 77");
        assertThat(row.get("email_private")).isEqualTo("ada.private@example.com");
        assertThat(row.get("email_business")).isEqualTo("ada.biz@example.com");
        assertThat(row.get("prefer_mail_to_business_mail")).isEqualTo(true);

        assertThat(row.get("firstname"))
                .as("name fields are admin-only — the self-edit must leave them untouched")
                .isEqualTo("Ada");
        assertThat(row.get("lastname")).isEqualTo("Lovelace");
        assertThat(row.get("midname")).isEqualTo("M");
    }

    @Test
    void patchPerson_resolvesCallerFromJwt_neverTouchesAnotherPrincipalsPerson() {
        UUID subA = UUID.randomUUID();
        UUID subB = UUID.randomUUID();
        UUID personA = seedPerson("Ada", "Lovelace", null);
        UUID personB = seedPerson("Grace", "Hopper", null);
        seedUser(subA, "meperson-it-self", personA);
        seedUser(subB, "meperson-it-other", personB);

        ResponseEntity<String> res = patch("/api/v1/me/person", Map.of(
                "city", "A-City"), pilotToken(subA));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(jdbc.queryForObject(
                "SELECT city FROM t_person WHERE id = ?::uuid", String.class, personA.toString()))
                .isEqualTo("A-City");

        Map<String, Object> rowB = jdbc.queryForMap(
                "SELECT firstname, city FROM t_person WHERE id = ?::uuid", personB.toString());
        assertThat(rowB.get("firstname")).isEqualTo("Grace");
        assertThat(rowB.get("city")).isNull();
    }

    @Test
    void patchPerson_callerWithNoLinkedPerson_returns409_notServerError() {
        UUID kcSub = UUID.randomUUID();
        seedUser(kcSub, "meperson-it-noperson", null);

        ResponseEntity<String> res = patch("/api/v1/me/person", Map.of(
                "city", "Nowhere"), pilotToken(kcSub));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private UUID seedPerson(String firstname, String lastname, String midname) {
        UUID personId = UUID.randomUUID();
        jdbc.update("INSERT INTO t_person (id, firstname, lastname, midname, company_name) "
                        + "VALUES (?::uuid, ?, ?, ?, ?)",
                personId.toString(), firstname, lastname, midname,
                TEARDOWN_MARKER_COMPANY_NAME);
        return personId;
    }

    private UUID seedUser(UUID kcSub, String username, UUID personId) {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, person_id,
                                    notification_email, phone_number, remarks, language_id,
                                    keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?::uuid, ?, NULL, NULL, ?::uuid, ?::uuid)
                """,
                userId.toString(), CLUB_UUID.toString(), username, "Friendly " + username,
                personId == null ? null : personId.toString(),
                username + "@example.com", LANG_DE_UUID.toString(), kcSub.toString());
        return userId;
    }

    private String pilotToken(UUID kcSub) {
        return jwts.mint(c -> c
                .subject(kcSub.toString())
                .claim("clubId", CLUB_UUID.toString())
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(
                RequestEntity.method(HttpMethod.GET, URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build(),
                String.class);
    }

    private ResponseEntity<String> patch(String path, Object body, String token) {
        return rest.exchange(
                RequestEntity.method(HttpMethod.PATCH, URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .body(body),
                String.class);
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON: " + json, e);
        }
    }
}
