package ch.alpenflight.audit.web;

import static ch.alpenflight.audit.web.AuditTestSupport.preCleanAuditRowsThatOutliveTestRollback;
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
import org.junit.jupiter.api.Disabled;
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
        preCleanAuditRowsThatOutliveTestRollback(jdbc, SYSADMIN_TENANT);
        sysadminToken = jwts.mint(c -> c
                .claim("clubId", SYSADMIN_TENANT.toString())
                .claim("realm_access", Map.of("roles", List.of("SYSTEM_ADMINISTRATOR"))));
    }

    @Test
    void list_returns_committed_audit_rows_under_caller_tenant() throws Exception {
        ResponseEntity<String> created = post("/api/v1/clubs",
                createPayload("AdminListed", "admin-listed-" + suffix(), "ALS" + shortSuffix()),
                sysadminToken);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> res = rest.exchange(
                authed(RequestEntity.get(URI.create("/api/v1/admin/audit-events")), sysadminToken).build(),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = JSON.readTree(res.getBody());
        JsonNode items = body.get("items");
        assertThat(items.isArray()).as("response is paginated; items is the array").isTrue();
        assertThat(items.size()).isGreaterThanOrEqualTo(1);
        assertThat(items.get(0).get("action").asText()).isEqualTo("CREATE");
        assertThat(items.get(0).get("targetEntityType").asText()).isEqualTo("Club");
        assertThat(body.has("hasMore")).as("cursor-pagination metadata").isTrue();
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
        for (JsonNode row : body.get("items")) {
            assertThat(row.get("action").asText()).isEqualTo("UPDATE");
        }
    }

    @Test
    void target_entity_type_filter_excludes_non_matching_rows() throws Exception {
        UUID locationTargetId =
                insertAuditRowDirectlySinceTheListenerWritesOneEntityTypePerEndpoint("Location");
        UUID aircraftTargetId =
                insertAuditRowDirectlySinceTheListenerWritesOneEntityTypePerEndpoint("Aircraft");

        JsonNode filtered = list("/api/v1/admin/audit-events?targetEntityType=Location");
        assertThat(targetIds(filtered))
                .as("filtering by targetEntityType=Location returns the Location row")
                .contains(locationTargetId.toString());
        assertThat(targetIds(filtered))
                .as("the Aircraft row is EXCLUDED by the filter, not merely all-visible-are-Location")
                .doesNotContain(aircraftTargetId.toString());
        for (JsonNode row : filtered.get("items")) {
            assertThat(row.get("targetEntityType").asText()).isEqualTo("Location");
        }

        JsonNode unfiltered = list("/api/v1/admin/audit-events");
        assertThat(targetIds(unfiltered))
                .as("without the filter BOTH rows return — so the exclusion is the filter's doing, not seeding")
                .contains(locationTargetId.toString(), aircraftTargetId.toString());
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
    void list_denies_plain_pilot() {
        String token = jwts.mint(c -> c
                .claim("clubId", SYSADMIN_TENANT.toString())
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));

        ResponseEntity<String> res = rest.exchange(
                authed(RequestEntity.get(URI.create("/api/v1/admin/audit-events")), token).build(),
                String.class);

        assertThat(res.getStatusCode())
                .as("a PILOT holds a tenant context but no admin authority — the role gate "
                        + "denies before any tenant-scoped read")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void list_denies_unauthenticated_request() {
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/admin/audit-events")).build(),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Disabled("S-023 — UnscopedTenantContext not yet shipped; "
            + "until then SYSTEM_ADMINISTRATOR sees only its own JWT-claimed tenant, "
            + "identical to CLUB_ADMINISTRATOR. When S-023 lands, this test pins "
            + "that cross-tenant access requires the explicit unscoped context "
            + "(otherwise 403, even for SYSADMIN).")
    void cross_tenant_admin_read_blocked() {
    }

    @Test
    void list_allows_club_administrator() throws Exception {
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
        assertThat(body.get("items").isArray()).isTrue();
    }

    private UUID insertAuditRowDirectlySinceTheListenerWritesOneEntityTypePerEndpoint(
            String targetEntityType) {
        UUID targetId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO t_mutation_audit_event "
                        + "(id, tenant_club_id, action, target_entity_type, target_entity_id) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?, ?::uuid)",
                UUID.randomUUID().toString(), SYSADMIN_TENANT.toString(),
                "CREATE", targetEntityType, targetId.toString());
        return targetId;
    }

    private JsonNode list(String path) throws Exception {
        ResponseEntity<String> res = rest.exchange(
                authed(RequestEntity.get(URI.create(path)), sysadminToken).build(),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return JSON.readTree(res.getBody());
    }

    private static List<String> targetIds(JsonNode page) {
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode row : page.get("items")) {
            JsonNode target = row.get("targetEntityId");
            if (target != null && !target.isNull()) {
                ids.add(target.asText());
            }
        }
        return ids;
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
