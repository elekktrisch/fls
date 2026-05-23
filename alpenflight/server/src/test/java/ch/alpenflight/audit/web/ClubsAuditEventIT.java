package ch.alpenflight.audit.web;

import static ch.alpenflight.audit.web.AuditTestSupport.assertSingleEventForTarget;
import static ch.alpenflight.audit.web.AuditTestSupport.findByTarget;
import static ch.alpenflight.audit.web.AuditTestSupport.parseSnapshot;
import static ch.alpenflight.audit.web.AuditTestSupport.truncateForTenant;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.id.ClubId;
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
 * Exercises the Clubs CRUD slice through HTTP and asserts that each
 * successful mutation lands exactly one {@code mutation_audit_event} row
 * with the right action + actor + snapshot semantics. Failed-mutation
 * coverage (the synthetic-failure path) lives in
 * {@link AuditFailedRequestIT}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class ClubsAuditEventIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    void successful_post_emits_event_with_after_state_and_no_before() {
        String slug = "audit-post-" + suffix();
        ResponseEntity<String> res = post("/api/v1/clubs",
                createPayload("AlpsAudit POST", slug, "APP" + shortSuffix()));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID createdId = ClubId.parse(readJson(res).get("id").asText()).value();

        Map<String, Object> row = assertSingleEventForTarget(jdbc, SYSADMIN_TENANT, createdId);

        assertThat(row.get("action")).isEqualTo("CREATE");
        assertThat(row.get("target_entity_type")).isEqualTo("Club");
        assertThat(row.get("failed")).as("row=%s", row).isEqualTo(false);
        assertThat(row.get("before_state"))
                .as("CREATE has no before-state").isNull();

        JsonNode after = parseSnapshot(row.get("after_state"));
        assertThat(after.get("name").asText()).isEqualTo("AlpsAudit POST");
        assertThat(after.get("slug").asText()).isEqualTo(slug);
        assertThat(after.has("clubKey")).isTrue();
    }

    @Test
    void successful_put_emits_event_with_before_and_after_state() {
        String slug = "audit-put-" + suffix();
        ResponseEntity<String> created = post("/api/v1/clubs",
                createPayload("Original", slug, "ORG" + shortSuffix()));
        UUID id = ClubId.parse(readJson(created).get("id").asText()).value();
        truncateForTenant(jdbc, SYSADMIN_TENANT);

        ResponseEntity<String> res = put("/api/v1/clubs/" + readJson(created).get("id").asText(),
                updatePayload("Renamed", slug, true));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> row = assertSingleEventForTarget(jdbc, SYSADMIN_TENANT, id);
        assertThat(row.get("action")).isEqualTo("UPDATE");

        JsonNode before = parseSnapshot(row.get("before_state"));
        JsonNode after = parseSnapshot(row.get("after_state"));
        assertThat(before.get("name").asText()).isEqualTo("Original");
        assertThat(after.get("name").asText()).isEqualTo("Renamed");
        assertThat(before.get("publicRegistrationEnabled").asBoolean()).isFalse();
        assertThat(after.get("publicRegistrationEnabled").asBoolean()).isTrue();
    }

    @Test
    void successful_delete_emits_event_with_before_state_and_after_state_null() {
        String slug = "audit-del-" + suffix();
        ResponseEntity<String> created = post("/api/v1/clubs",
                createPayload("DoomedClub", slug, "DOM" + shortSuffix()));
        String externalId = readJson(created).get("id").asText();
        UUID id = ClubId.parse(externalId).value();
        truncateForTenant(jdbc, SYSADMIN_TENANT);

        ResponseEntity<Void> del = rest.exchange(authed(
                        RequestEntity.delete(URI.create("/api/v1/clubs/" + externalId))).build(),
                Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Map<String, Object> row = assertSingleEventForTarget(jdbc, SYSADMIN_TENANT, id);
        assertThat(row.get("action")).isEqualTo("DELETE");
        assertThat(row.get("after_state"))
                .as("DELETE has no after-state").isNull();

        JsonNode before = parseSnapshot(row.get("before_state"));
        assertThat(before.get("name").asText()).isEqualTo("DoomedClub");
        assertThat(before.get("slug").asText()).isEqualTo(slug);
    }

    @Test
    void audit_row_carries_request_id_from_response_header() {
        String slug = "audit-rid-" + suffix();
        String correlation = "trace-" + suffix();
        ResponseEntity<String> res = rest.exchange(authed(
                        RequestEntity.post(URI.create("/api/v1/clubs"))
                                .header("X-Request-Id", correlation)
                                .contentType(MediaType.APPLICATION_JSON))
                        .body(createPayload("CorrelatedClub", slug, "COR" + shortSuffix())),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = ClubId.parse(readJson(res).get("id").asText()).value();

        Map<String, Object> row = assertSingleEventForTarget(jdbc, SYSADMIN_TENANT, id);
        assertThat(row.get("request_id")).isEqualTo(correlation);
    }

    @Test
    void list_endpoint_emits_no_audit_event() {
        ResponseEntity<String> res = get("/api/v1/clubs");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        // List is a GET, so neither service emit nor synthetic failure should fire.
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM mutation_audit_event WHERE tenant_club_id = ?::uuid",
                SYSADMIN_TENANT.toString());
        assertThat(rows).isEmpty();
    }

    @Test
    void audit_row_captures_actor_keycloak_sub_from_jwt_uuid_sub() {
        UUID expectedSub = UUID.randomUUID();
        String uuidSubToken = jwts.mint(c -> c
                .subject(expectedSub.toString())
                .claim("clubId", SYSADMIN_TENANT.toString())
                .claim("realm_access", Map.of("roles", List.of("SYSTEM_ADMINISTRATOR"))));

        String slug = "audit-actor-" + suffix();
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.post(URI.create("/api/v1/clubs"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + uuidSubToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(createPayload("ActorClub", slug, "ACT" + shortSuffix())),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = ClubId.parse(readJson(res).get("id").asText()).value();

        Map<String, Object> row = assertSingleEventForTarget(jdbc, SYSADMIN_TENANT, id);
        assertThat(row.get("actor_keycloak_sub"))
                .as("JWT sub lands on the audit row as the immutable forensic key (raw string)")
                .isEqualTo(expectedSub.toString());
        assertThat(row.get("system_actor"))
                .as("Authenticated JWT principal → system_actor=false")
                .isEqualTo(false);
    }

    @Test
    void audit_row_captures_actor_keycloak_sub_from_federated_non_uuid_sub() {
        // Google's numeric IDs / Auth0 custom subs are non-UUID strings.
        // The audit trail records them verbatim and still classifies the
        // row as a human actor (system_actor=false).
        String federatedSub = "google-oauth2|108426310582738501";
        String federatedToken = jwts.mint(c -> c
                .subject(federatedSub)
                .claim("clubId", SYSADMIN_TENANT.toString())
                .claim("realm_access", Map.of("roles", List.of("SYSTEM_ADMINISTRATOR"))));

        String slug = "audit-fed-" + suffix();
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.post(URI.create("/api/v1/clubs"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + federatedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(createPayload("FedClub", slug, "FED" + shortSuffix())),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = ClubId.parse(readJson(res).get("id").asText()).value();

        Map<String, Object> row = assertSingleEventForTarget(jdbc, SYSADMIN_TENANT, id);
        assertThat(row.get("actor_keycloak_sub"))
                .as("Federated non-UUID sub recorded verbatim")
                .isEqualTo(federatedSub);
        assertThat(row.get("system_actor"))
                .as("Federated authenticated principal still has system_actor=false")
                .isEqualTo(false);
    }

    @Test
    void failed_409_duplicate_slug_emits_no_success_row() {
        String slug = "audit-dup-" + suffix();
        ResponseEntity<String> first = post("/api/v1/clubs",
                createPayload("FirstClub", slug, "FRC" + shortSuffix()));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID firstId = ClubId.parse(readJson(first).get("id").asText()).value();
        truncateForTenant(jdbc, SYSADMIN_TENANT);

        ResponseEntity<String> dup = post("/api/v1/clubs",
                createPayload("DupClub", slug, "DUP" + shortSuffix()));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        List<Map<String, Object>> firstTargetRows = findByTarget(jdbc, SYSADMIN_TENANT, firstId);
        assertThat(firstTargetRows)
                .as("No success row for the first club — the first POST committed before truncate")
                .isEmpty();
        // The synthetic failure row for the dup POST has target_entity_id = null,
        // so query by tenant + failed=true + action=CREATE.
        List<Map<String, Object>> failedRows = jdbc.queryForList(
                "SELECT * FROM mutation_audit_event "
                        + "WHERE tenant_club_id = ?::uuid AND failed = true AND action = 'CREATE'",
                SYSADMIN_TENANT.toString());
        assertThat(failedRows)
                .as("409 conflict should produce one synthetic failure row")
                .hasSize(1);
        // pg JDBC maps SMALLINT to Integer in queryForList default mapping
        assertThat(failedRows.get(0).get("http_status")).isEqualTo(409);
        assertThat(failedRows.get(0).get("target_entity_type")).isEqualTo("Club");
    }

    // ----- helpers -----

    private ResponseEntity<String> get(String path) {
        return rest.exchange(authed(RequestEntity.get(URI.create(path))).build(), String.class);
    }

    private ResponseEntity<String> post(String path, Map<String, Object> body) {
        return rest.exchange(authed(
                        RequestEntity.post(URI.create(path))
                                .contentType(MediaType.APPLICATION_JSON))
                        .body(body),
                String.class);
    }

    private ResponseEntity<String> put(String path, Map<String, Object> body) {
        return rest.exchange(authed(
                        RequestEntity.put(URI.create(path))
                                .contentType(MediaType.APPLICATION_JSON))
                        .body(body),
                String.class);
    }

    private <T extends RequestEntity.BodyBuilder> T authed(T builder) {
        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + sysadminToken);
        return builder;
    }

    private RequestEntity.HeadersBuilder<?> authed(RequestEntity.HeadersBuilder<?> builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + sysadminToken);
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
