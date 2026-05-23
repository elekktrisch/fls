package ch.alpenflight.audit.web;

import static ch.alpenflight.audit.web.AuditTestSupport.truncateForTenant;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Drives {@code GET /api/v1/admin/audit-events} — the read surface S-056's
 * admin UI consumes.
 *
 * <ul>
 *   <li>CLUB_ADMINISTRATOR + SYSTEM_ADMINISTRATOR can both list, scoped
 *       to their JWT-claimed tenant via Hibernate's {@code @TenantId}
 *       filter on the entity.</li>
 *   <li>FLIGHT_OPERATOR + unauthenticated callers are gated by the role
 *       predicate on the controller.</li>
 *   <li>Filtering by {@code action} / {@code targetEntityType} works
 *       additively.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class AuditAdminControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID SYSADMIN_TENANT = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final String SEED_COUNTRY_ID = "019e2e15-2c00-74be-8000-0000000004be";
    private static final String SEED_CLUB_STATE_ID = "019e2e15-2c00-7bb8-8000-000000000bb8";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    private String sysadminToken;

    @BeforeEach
    void setUp() {
        truncateForTenant(jdbc, SYSADMIN_TENANT);
        sysadminToken = jwts.mint(c -> c
                .claim("clubId", SYSADMIN_TENANT.toString())
                .claim("realm_access", Map.of("roles", List.of("SYSTEM_ADMINISTRATOR"))));
    }

    @Test
    void list_returns_committed_audit_rows_under_caller_tenant() throws Exception {
        // Seed: one CREATE event via the Clubs slice.
        ResponseEntity<String> created = post("/api/v1/clubs",
                createPayload("AdminListed", "admin-listed-" + suffix(), "ALS" + shortSuffix()),
                sysadminToken);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> res = rest.exchange(
                authed(RequestEntity.get(URI.create("/api/v1/admin/audit-events")), sysadminToken).build(),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = JSON.readTree(res.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isGreaterThanOrEqualTo(1);
        assertThat(body.get(0).get("action").asText()).isEqualTo("CREATE");
        assertThat(body.get(0).get("targetEntityType").asText()).isEqualTo("Club");
    }

    @Test
    void list_filters_by_action() throws Exception {
        String slug = "admin-filter-" + suffix();
        ResponseEntity<String> created = post("/api/v1/clubs",
                createPayload("FilterableClub", slug, "FLT" + shortSuffix()),
                sysadminToken);
        String id = JSON.readTree(created.getBody()).get("id").asText();
        rest.exchange(
                authed(RequestEntity.put(URI.create("/api/v1/clubs/" + id))
                        .contentType(MediaType.APPLICATION_JSON), sysadminToken)
                        .body(updatePayload("RenamedFilter", slug, true)),
                String.class);

        ResponseEntity<String> res = rest.exchange(
                authed(RequestEntity.get(URI.create(
                        "/api/v1/admin/audit-events?action=UPDATE")), sysadminToken).build(),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = JSON.readTree(res.getBody());
        for (JsonNode row : body) {
            assertThat(row.get("action").asText()).isEqualTo("UPDATE");
        }
    }

    @Test
    void list_denies_flight_operator() {
        String token = jwts.mint(c -> c
                .claim("clubId", SYSADMIN_TENANT.toString())
                .claim("realm_access", Map.of("roles", List.of("FLIGHT_OPERATOR"))));

        ResponseEntity<String> res = rest.exchange(
                authed(RequestEntity.get(URI.create("/api/v1/admin/audit-events")), token).build(),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void list_denies_unauthenticated_request() {
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/admin/audit-events")).build(),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void list_allows_club_administrator() throws Exception {
        // Plant a Club + its audit row using the sysadmin so the seed-club tenant has data.
        post("/api/v1/clubs",
                createPayload("ClubAdminList", "club-admin-list-" + suffix(), "CAL" + shortSuffix()),
                sysadminToken);

        String token = jwts.mint(c -> c
                .claim("clubId", SYSADMIN_TENANT.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));

        ResponseEntity<String> res = rest.exchange(
                authed(RequestEntity.get(URI.create("/api/v1/admin/audit-events")), token).build(),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = JSON.readTree(res.getBody());
        assertThat(body.isArray()).isTrue();
    }

    private ResponseEntity<String> post(String path, Map<String, Object> body, String token) {
        return rest.exchange(
                authed(RequestEntity.post(URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON), token).body(body),
                String.class);
    }

    private static <T extends RequestEntity.BodyBuilder> T authed(T builder, String token) {
        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return builder;
    }

    private static RequestEntity.HeadersBuilder<?> authed(RequestEntity.HeadersBuilder<?> builder,
                                                          String token) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static Map<String, Object> createPayload(String name, String slug, String clubKey) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("name", name);
        n.put("slug", slug);
        n.put("clubKey", clubKey);
        n.put("publicRegistrationEnabled", false);
        n.put("countryId", SEED_COUNTRY_ID);
        n.put("clubStateId", SEED_CLUB_STATE_ID);
        return n;
    }

    private static Map<String, Object> updatePayload(String name, String slug, boolean publicReg) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("name", name);
        n.put("slug", slug);
        n.put("publicRegistrationEnabled", publicReg);
        n.put("countryId", SEED_COUNTRY_ID);
        n.put("clubStateId", SEED_CLUB_STATE_ID);
        return n;
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static String shortSuffix() {
        String s = Long.toString(System.nanoTime(), 36).toUpperCase(java.util.Locale.ROOT);
        return s.length() > 6 ? s.substring(s.length() - 6) : s;
    }
}
