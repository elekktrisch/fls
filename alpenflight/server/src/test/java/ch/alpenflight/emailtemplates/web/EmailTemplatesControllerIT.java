package ch.alpenflight.emailtemplates.web;

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
import org.jspecify.annotations.Nullable;
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
 * Full-stack HTTP integration test for the email-template override slice. Pins:
 *
 * <ul>
 *   <li>union read — file defaults ∪ own-club overrides, override wins;</li>
 *   <li>clone-on-customize — insert then update-in-place (same identity);</li>
 *   <li>reset — delete the override → file default re-surfaces;</li>
 *   <li>authz — non-admin write is 403;</li>
 *   <li>tenancy — a club admin sees only own-club overrides, never another club's.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class EmailTemplatesControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLUB_A = "019e30c3-2c00-7001-8000-000000000001";
    private static final String CLUB_B = "019e30c3-2c00-7001-8000-0000000ab302";
    private static final String FILE_DEFAULT_KEY = "planningday-ok";
    private static final String LOCALE = "de";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    @BeforeEach
    void seedClubB_and_cleanOverrides() {
        UUID countryId = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        UUID clubStateId = jdbc.queryForObject("SELECT id FROM t_club_state LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO t_club (id, clubname, club_key, country_id, club_state_id, slug, public_registration_enabled)
                VALUES (?::uuid, ?, ?, ?::uuid, ?::uuid, ?, false)
                ON CONFLICT (id) DO NOTHING
                """,
                CLUB_B, "Email IT Test Club B", "EML_B",
                countryId.toString(), clubStateId.toString(), "email-it-test-b");
        jdbc.update("DELETE FROM t_email_template WHERE club_id IN (?::uuid, ?::uuid)", CLUB_A, CLUB_B);
    }

    @Test
    void unionRead_fileDefaultsThenOverrideWins() {
        String admin = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");

        JsonNode beforeList = readJson(get("/api/v1/email-templates", admin));
        JsonNode defaultEntry = entryFor(beforeList, FILE_DEFAULT_KEY, LOCALE);
        assertThat(defaultEntry.get("source").asText()).isEqualTo("FILE_DEFAULT");
        // File defaults carry no stored subject — absent or JSON-null on the wire.
        assertThat(isAbsentOrNull(defaultEntry.get("subject"))).isTrue();
        assertThat(defaultEntry.get("body").asText()).contains("Flugbetriebstag");

        ResponseEntity<String> saved = put(
                "/api/v1/email-templates/" + FILE_DEFAULT_KEY + "/" + LOCALE,
                Map.of("subject", "Custom subject", "body", "<p>Custom body</p>"), admin);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode afterList = readJson(get("/api/v1/email-templates", admin));
        JsonNode overrideEntry = entryFor(afterList, FILE_DEFAULT_KEY, LOCALE);
        assertThat(overrideEntry.get("source").asText()).isEqualTo("CLUB_OVERRIDE");
        assertThat(overrideEntry.get("subject").asText()).isEqualTo("Custom subject");
        assertThat(overrideEntry.get("body").asText()).isEqualTo("<p>Custom body</p>");
        // The override suppresses the file default — one entry, not two, for the key+locale.
        assertThat(countEntries(afterList, FILE_DEFAULT_KEY, LOCALE)).isEqualTo(1);
    }

    @Test
    void customize_upsert_insertThenUpdateInPlace() {
        String admin = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        String path = "/api/v1/email-templates/" + FILE_DEFAULT_KEY + "/" + LOCALE;

        put(path, Map.of("subject", "v1", "body", "b1"), admin);
        put(path, Map.of("subject", "v2", "body", "b2"), admin);

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM t_email_template WHERE club_id = ?::uuid "
                        + "AND template_key = ? AND language_locale = ?",
                Integer.class, CLUB_A, FILE_DEFAULT_KEY, LOCALE);
        assertThat(rows).isEqualTo(1);

        JsonNode entry = entryFor(readJson(get("/api/v1/email-templates", admin)), FILE_DEFAULT_KEY, LOCALE);
        assertThat(entry.get("subject").asText()).isEqualTo("v2");
        assertThat(entry.get("body").asText()).isEqualTo("b2");
    }

    @Test
    void reset_deletesOverride_fileDefaultResurfaces() {
        String admin = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        String path = "/api/v1/email-templates/" + FILE_DEFAULT_KEY + "/" + LOCALE;
        put(path, Map.of("subject", "s", "body", "b"), admin);

        ResponseEntity<String> del = delete(path, admin);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode entry = entryFor(readJson(get("/api/v1/email-templates", admin)), FILE_DEFAULT_KEY, LOCALE);
        assertThat(entry.get("source").asText()).isEqualTo("FILE_DEFAULT");

        assertThat(delete(path, admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void nonAdmin_cannotMutate_returns403() {
        String operator = mintToken(CLUB_A, "FLIGHT_OPERATOR");
        ResponseEntity<String> res = put(
                "/api/v1/email-templates/" + FILE_DEFAULT_KEY + "/" + LOCALE,
                Map.of("subject", "s", "body", "b"), operator);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void tenancy_clubAdminSeesOnlyOwnOverrides() {
        String adminB = mintToken(CLUB_B, "CLUB_ADMINISTRATOR");
        put("/api/v1/email-templates/" + FILE_DEFAULT_KEY + "/" + LOCALE,
                Map.of("subject", "B-subject", "body", "B-body"), adminB);

        String adminA = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        JsonNode listA = readJson(get("/api/v1/email-templates", adminA));
        JsonNode entryA = entryFor(listA, FILE_DEFAULT_KEY, LOCALE);
        // A never sees B's override — the file default still shows for A.
        assertThat(entryA.get("source").asText()).isEqualTo("FILE_DEFAULT");
    }

    // ----- helpers -----

    private static boolean isAbsentOrNull(@Nullable JsonNode node) {
        return node == null || node.isNull();
    }

    private static JsonNode entryFor(JsonNode array, String key, String locale) {
        return matching(array, key, locale).stream().findFirst()
                .orElseThrow(() -> new AssertionError("no union entry for " + key + "/" + locale));
    }

    private static int countEntries(JsonNode array, String key, String locale) {
        return matching(array, key, locale).size();
    }

    private static List<JsonNode> matching(JsonNode array, String key, String locale) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .filter(n -> n.get("templateKey").asText().equals(key)
                        && n.get("languageLocale").asText().equals(locale))
                .toList();
    }

    private String mintToken(@Nullable String clubId, String role) {
        Consumer<com.nimbusds.jwt.JWTClaimsSet.Builder> body = c -> {
            if (clubId != null) {
                c.claim("clubId", clubId);
            }
            c.claim("realm_access", Map.of("roles", List.of(role)));
        };
        return jwts.mint(body);
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(RequestEntity.get(URI.create(path))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).build(), String.class);
    }

    private ResponseEntity<String> put(String path, Object body, String token) {
        return rest.exchange(RequestEntity.put(URI.create(path))
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .body(body), String.class);
    }

    private ResponseEntity<String> delete(String path, String token) {
        return rest.exchange(RequestEntity.delete(URI.create(path))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).build(), String.class);
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }
}
