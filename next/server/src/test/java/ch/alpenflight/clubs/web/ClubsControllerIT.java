package ch.alpenflight.clubs.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full-stack HTTP integration test for the Clubs CRUD slice under the
 * {@code mock-auth} profile (S-048). Asserts the API surface, the
 * {@code @PreAuthorize} happy-path under a sysadmin principal, and the
 * soft-delete filtering invariant.
 *
 * <p>Per-test isolation relies on time-stamped unique slugs / clubKeys —
 * the V5 seed row stays untouched.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles({"test", "mock-auth"})
class ClubsControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @Test
    void listClubs_returns_200_with_seeded_row() {
        ResponseEntity<String> res = rest.getForEntity("/api/v1/clubs", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull().contains("seed-club-1");
    }

    @Test
    void createClub_valid_returns_201_with_location_and_body() {
        String slug = "create-" + suffix();
        ResponseEntity<String> res = post("/api/v1/clubs",
                createPayload("Mountain Soaring", slug, "MTN" + shortSuffix()));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = readJson(res);
        assertThat(body.get("name").asText()).isEqualTo("Mountain Soaring");
        assertThat(body.get("slug").asText()).isEqualTo(slug);
        URI loc = res.getHeaders().getLocation();
        assertThat(loc).isNotNull();
        assertThat(loc.getPath()).isEqualTo("/api/v1/clubs/" + body.get("id").asText());
    }

    @Test
    void createClub_blankName_returns_400() {
        ResponseEntity<String> res = post("/api/v1/clubs",
                createPayload("", "blank-" + suffix(), "BNK" + shortSuffix()));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createClub_duplicateSlug_returns_409() {
        String slug = "dup-" + suffix();
        ResponseEntity<String> first = post("/api/v1/clubs",
                createPayload("Alps Gliding", slug, "ALP" + shortSuffix()));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = post("/api/v1/clubs",
                createPayload("Alps Gliding 2", slug, "ALP" + shortSuffix()));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void updateClub_existing_returns_200_with_updated_body() {
        String slug = "orig-" + suffix();
        ResponseEntity<String> created = post("/api/v1/clubs",
                createPayload("Original Name", slug, "ORG" + shortSuffix()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = readJson(created).get("id").asText();

        ResponseEntity<String> res = put("/api/v1/clubs/" + id,
                updatePayload("Renamed Club", slug, true));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.get("name").asText()).isEqualTo("Renamed Club");
        assertThat(body.get("publicRegistrationEnabled").asBoolean()).isTrue();
    }

    @Test
    void updateClub_unknownId_returns_404() {
        // Valid ClubId external form but no Club with that UUID exists.
        ClubId ghost = ClubId.of(new java.util.UUID(0L, 0L));
        ResponseEntity<String> res = put(
                "/api/v1/clubs/" + ghost,
                updatePayload("x", "ghost-" + suffix(), false));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getClub_malformed_id_returns_400() {
        // Not a clb--prefixed ClubId external form → conversion failure.
        ResponseEntity<String> res = rest.getForEntity(
                "/api/v1/clubs/00000000-0000-0000-0000-000000000000", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deleteClub_existing_returns_204_then_404_on_get() {
        String slug = "doomed-" + suffix();
        ResponseEntity<String> created = post("/api/v1/clubs",
                createPayload("Doomed Club", slug, "DMD" + shortSuffix()));
        String id = readJson(created).get("id").asText();

        ResponseEntity<Void> del = rest.exchange(
                RequestEntity.delete(URI.create("/api/v1/clubs/" + id)).build(),
                Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> after = rest.getForEntity("/api/v1/clubs/" + id, String.class);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteClub_softDeletes_rather_than_physical_remove() {
        String slug = "soft-" + suffix();
        ResponseEntity<String> created = post("/api/v1/clubs",
                createPayload("Soft Deleted", slug, "SFT" + shortSuffix()));
        String externalId = readJson(created).get("id").asText();
        java.util.UUID rawId = ClubId.parse(externalId).value();
        rest.exchange(RequestEntity.delete(URI.create("/api/v1/clubs/" + externalId)).build(), Void.class);

        // Row must still exist in the DB with deleted_on stamped — soft, not hard.
        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM club WHERE id = ?::uuid AND deleted_on IS NOT NULL",
                Integer.class, rawId.toString());
        assertThat(rowCount)
                .as("Soft-delete must leave the row in place with deleted_on stamped")
                .isEqualTo(1);

        // List endpoint excludes soft-deleted rows.
        ResponseEntity<String> list = rest.getForEntity("/api/v1/clubs", String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).doesNotContain(slug);
    }

    // ----- helpers -----

    private ResponseEntity<String> post(String path, Map<String, Object> body) {
        return rest.exchange(
                RequestEntity.post(URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
                String.class);
    }

    private ResponseEntity<String> put(String path, Map<String, Object> body) {
        return rest.exchange(
                RequestEntity.put(URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
                String.class);
    }

    private static Map<String, Object> createPayload(String name, String slug, String clubKey) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("name", name);
        n.put("slug", slug);
        n.put("clubKey", clubKey);
        n.put("publicRegistrationEnabled", false);
        return n;
    }

    private static Map<String, Object> updatePayload(String name, String slug, boolean publicReg) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("name", name);
        n.put("slug", slug);
        n.put("publicRegistrationEnabled", publicReg);
        return n;
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response body: " + res.getBody(), e);
        }
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static String shortSuffix() {
        String s = Long.toString(System.nanoTime(), 36).toUpperCase(java.util.Locale.ROOT);
        return s.length() > 6 ? s.substring(s.length() - 6) : s;
    }
}
