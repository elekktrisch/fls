package ch.alpenflight.me.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
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

/**
 * HTTP slice for {@code PATCH /api/v1/me/person} — the caller-scoped
 * Person-contact self-edit (J-4 T-06). The endpoint takes NO {@code :id}: the
 * Person being edited is resolved from the JWT {@code sub} → the caller's
 * {@code t_user} row → its {@code person_id}, so the isolation test below is
 * the structural proof that a caller can only ever mutate their own Person.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class MePersonControllerIT extends PostgresIntegrationTest {

    private static final UUID CLUB_UUID =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final UUID LANG_DE_UUID =
            UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    @BeforeEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM t_user WHERE username LIKE 'meperson-it-%'");
        jdbc.update("DELETE FROM t_person WHERE company_name = 'MePersonIT'");
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

        // Contact fields persisted on the caller's own Person row.
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
        // Email is normalised to lower-case by the Person aggregate.
        assertThat(row.get("email_private")).isEqualTo("ada.private@example.com");
        assertThat(row.get("email_business")).isEqualTo("ada.biz@example.com");
        assertThat(row.get("prefer_mail_to_business_mail")).isEqualTo(true);

        // Name fields are admin-only and must survive the self-edit untouched.
        assertThat(row.get("firstname")).isEqualTo("Ada");
        assertThat(row.get("lastname")).isEqualTo("Lovelace");
        assertThat(row.get("midname")).isEqualTo("M");
    }

    @Test
    void patchPerson_resolvesCallerFromJwt_neverTouchesAnotherPrincipalsPerson() {
        // Two principals, each with their own linked Person. The endpoint has
        // no :id — the Person to edit is resolved from principal A's JWT sub
        // via their user row. Principal B's Person must be untouched.
        UUID subA = UUID.randomUUID();
        UUID subB = UUID.randomUUID();
        UUID personA = seedPerson("Ada", "Lovelace", null);
        UUID personB = seedPerson("Grace", "Hopper", null);
        seedUser(subA, "meperson-it-self", personA);
        seedUser(subB, "meperson-it-other", personB);

        // A PATCHes with A's token — no id in the URL, nothing of B's in the body.
        ResponseEntity<String> res = patch("/api/v1/me/person", Map.of(
                "city", "A-City"), pilotToken(subA));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        // A's Person changed.
        assertThat(jdbc.queryForObject(
                "SELECT city FROM t_person WHERE id = ?::uuid", String.class, personA.toString()))
                .isEqualTo("A-City");

        // B's Person is completely untouched — the caller could not reach it.
        Map<String, Object> rowB = jdbc.queryForMap(
                "SELECT firstname, city FROM t_person WHERE id = ?::uuid", personB.toString());
        assertThat(rowB.get("firstname")).isEqualTo("Grace");
        assertThat(rowB.get("city")).isNull();
    }

    @Test
    void patchPerson_callerWithNoLinkedPerson_returns409_notServerError() {
        // A user row with person_id null (e.g. a sysadmin / unlinked principal).
        UUID kcSub = UUID.randomUUID();
        seedUser(kcSub, "meperson-it-noperson", null);

        ResponseEntity<String> res = patch("/api/v1/me/person", Map.of(
                "city", "Nowhere"), pilotToken(kcSub));

        // Clean 409 — not a 500.
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private UUID seedPerson(String firstname, String lastname, String midname) {
        UUID personId = UUID.randomUUID();
        // company_name carries the teardown marker so cleanFixtures can purge
        // without colliding with the firstname/lastname/midname the assertions
        // read back literally.
        jdbc.update("INSERT INTO t_person (id, firstname, lastname, midname, company_name) "
                        + "VALUES (?::uuid, ?, ?, ?, 'MePersonIT')",
                personId.toString(), firstname, lastname, midname);
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

    private ResponseEntity<String> patch(String path, Object body, String token) {
        return rest.exchange(
                RequestEntity.method(HttpMethod.PATCH, URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .body(body),
                String.class);
    }
}
