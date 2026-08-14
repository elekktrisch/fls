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
class MePersonLicencesControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID CLUB_UUID =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final UUID LANG_DE_UUID =
            UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    @BeforeEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM t_user WHERE username LIKE 'melic-it-%'");
        jdbc.update("DELETE FROM t_person WHERE company_name = 'MeLicencesIT'");
        jdbc.update("DELETE FROM t_mutation_audit_event WHERE target_entity_type = 'PersonLicences'");
    }

    @Test
    void getLicences_returnsCallersOwnLicenceShape() {
        UUID kcSub = UUID.randomUUID();
        UUID personId = seedPerson("Amelia", "Earhart");
        jdbc.update("UPDATE t_person SET has_glider_pilot_licence = true, "
                        + "licence_number = 'CH-GLD-9999', "
                        + "medical_class2_expire_date = DATE '2027-09-30' "
                        + "WHERE id = ?::uuid", personId.toString());
        seedUser(kcSub, "melic-it-get", personId);

        ResponseEntity<String> res = get("/api/v1/me/person/licences", pilotToken(kcSub));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode body = parse(res.getBody());
        assertThat(body.get("hasGliderPilotLicence").asBoolean()).isTrue();
        assertThat(body.get("hasMotorPilotLicence").asBoolean()).isFalse();
        assertThat(body.get("licenceNumber").asText()).isEqualTo("CH-GLD-9999");
        assertThat(body.get("medicalClass2ExpireDate").asText()).isEqualTo("2027-09-30");
    }

    @Test
    void patchLicences_persistsAndReGetReflects() {
        UUID kcSub = UUID.randomUUID();
        UUID personId = seedPerson("Bessie", "Coleman");
        seedUser(kcSub, "melic-it-patch", personId);
        String token = pilotToken(kcSub);

        ResponseEntity<String> res = patch("/api/v1/me/person/licences", Map.of(
                "hasGliderPilotLicence", true,
                "hasTmgLicence", true,
                "licenceNumber", "CH-TMG-0007",
                "medicalClass1ExpireDate", "2028-01-31"), token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT has_glider_pilot_licence, has_tmg_licence, licence_number, "
                        + "medical_class1_expire_date FROM t_person WHERE id = ?::uuid",
                personId.toString());
        assertThat(row.get("has_glider_pilot_licence")).isEqualTo(true);
        assertThat(row.get("has_tmg_licence")).isEqualTo(true);
        assertThat(row.get("licence_number")).isEqualTo("CH-TMG-0007");
        assertThat(row.get("medical_class1_expire_date").toString()).isEqualTo("2028-01-31");

        JsonNode body = parse(get("/api/v1/me/person/licences", token).getBody());
        assertThat(body.get("hasGliderPilotLicence").asBoolean()).isTrue();
        assertThat(body.get("hasTmgLicence").asBoolean()).isTrue();
        assertThat(body.get("licenceNumber").asText()).isEqualTo("CH-TMG-0007");
        assertThat(body.get("medicalClass1ExpireDate").asText()).isEqualTo("2028-01-31");
    }

    @Test
    void patchLicences_emitsReadableBeforeAfterAuditDiff() {
        UUID kcSub = UUID.randomUUID();
        UUID personId = seedPerson("Harriet", "Quimby");
        seedUser(kcSub, "melic-it-audit", personId);

        ResponseEntity<String> res = patch("/api/v1/me/person/licences", Map.of(
                "hasGliderPilotLicence", true,
                "medicalClass2ExpireDate", "2029-06-30"), pilotToken(kcSub));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> auditRow = jdbc.queryForMap(
                "SELECT action, target_entity_type, target_entity_id, "
                        + "before_state::text AS before_state, after_state::text AS after_state "
                        + "FROM t_mutation_audit_event "
                        + "WHERE target_entity_type = 'PersonLicences' "
                        + "AND target_entity_id = ?::uuid "
                        + "ORDER BY occurred_at DESC LIMIT 1",
                personId.toString());
        assertThat(auditRow.get("action")).isEqualTo("UPDATE");

        JsonNode before = parse((String) auditRow.get("before_state"));
        JsonNode after = parse((String) auditRow.get("after_state"));
        assertThat(before.get("hasGliderPilotLicence").asText()).isEqualTo("false");
        assertThat(before.has("medicalClass2ExpireDate")
                && "[redacted]".equals(before.get("medicalClass2ExpireDate").asText())).isFalse();
        assertThat(after.get("hasGliderPilotLicence").asBoolean()).isTrue();
        assertThat(after.get("medicalClass2ExpireDate").asText()).isEqualTo("2029-06-30");
    }

    @Test
    void patchLicences_resolvesCallerFromJwt_neverTouchesAnotherPrincipalsLicences() {
        UUID subA = UUID.randomUUID();
        UUID subB = UUID.randomUUID();
        UUID personA = seedPerson("Ada", "Lovelace");
        UUID personB = seedPerson("Grace", "Hopper");
        seedUser(subA, "melic-it-self", personA);
        seedUser(subB, "melic-it-other", personB);

        ResponseEntity<String> res = patch("/api/v1/me/person/licences", Map.of(
                "hasGliderPilotLicence", true,
                "licenceNumber", "CH-A-0001"), pilotToken(subA));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(jdbc.queryForObject(
                "SELECT licence_number FROM t_person WHERE id = ?::uuid",
                String.class, personA.toString()))
                .isEqualTo("CH-A-0001");

        Map<String, Object> rowB = jdbc.queryForMap(
                "SELECT has_glider_pilot_licence, licence_number FROM t_person WHERE id = ?::uuid",
                personB.toString());
        assertThat(rowB.get("has_glider_pilot_licence")).isEqualTo(false);
        assertThat(rowB.get("licence_number")).isNull();
    }

    @Test
    void getLicences_callerWithNoLinkedPerson_returns409() {
        UUID kcSub = UUID.randomUUID();
        seedUser(kcSub, "melic-it-noperson", null);

        ResponseEntity<String> res = get("/api/v1/me/person/licences", pilotToken(kcSub));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private UUID seedPerson(String firstname, String lastname) {
        UUID personId = UUID.randomUUID();
        jdbc.update("INSERT INTO t_person (id, firstname, lastname, company_name) "
                        + "VALUES (?::uuid, ?, ?, 'MeLicencesIT')",
                personId.toString(), firstname, lastname);
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
