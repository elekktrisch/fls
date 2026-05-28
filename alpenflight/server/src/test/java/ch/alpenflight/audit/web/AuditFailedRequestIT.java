package ch.alpenflight.audit.web;

import static ch.alpenflight.audit.web.AuditTestSupport.truncateForTenant;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
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
 * Pins the {@link RequestAuditFilter} synthetic-failure contract. When a
 * mutating call returns 4xx or 5xx and no success-row was published by the
 * service layer, the filter emits exactly one {@code failed=true} row with
 * {@code http_status} populated.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class AuditFailedRequestIT extends PostgresIntegrationTest {

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
    void failed_400_validation_emits_failed_flag_with_no_after_state() {
        Map<String, Object> badPayload = new LinkedHashMap<>();
        badPayload.put("name", "");
        badPayload.put("slug", "validation-fail-" + suffix());
        badPayload.put("clubKey", "VAL" + shortSuffix());
        badPayload.put("publicRegistrationEnabled", false);
        badPayload.put("countryId", SEED_COUNTRY_ID);
        badPayload.put("clubStateId", SEED_CLUB_STATE_ID);

        ResponseEntity<String> res = post("/api/v1/clubs", badPayload);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        List<Map<String, Object>> failedRows = jdbc.queryForList(
                "SELECT * FROM t_mutation_audit_event "
                        + "WHERE tenant_club_id = ?::uuid AND failed = true",
                SYSADMIN_TENANT.toString());
        assertThat(failedRows).hasSize(1);
        Map<String, Object> row = failedRows.get(0);
        assertThat(row.get("action")).isEqualTo("CREATE");
        assertThat(row.get("target_entity_type")).isEqualTo("Club");
        // pg JDBC maps SMALLINT to Integer in queryForList default mapping
        assertThat(row.get("http_status")).isEqualTo(400);
        assertThat(row.get("after_state")).isNull();
        assertThat(row.get("failure_reason")).isNotNull();
    }

    @Test
    void successful_post_does_not_emit_synthetic_failure_row() {
        String slug = "no-double-" + suffix();
        Map<String, Object> goodPayload = new LinkedHashMap<>();
        goodPayload.put("name", "NoDoubleClub");
        goodPayload.put("slug", slug);
        goodPayload.put("clubKey", "NDC" + shortSuffix());
        goodPayload.put("publicRegistrationEnabled", false);
        goodPayload.put("countryId", SEED_COUNTRY_ID);
        goodPayload.put("clubStateId", SEED_CLUB_STATE_ID);

        ResponseEntity<String> res = post("/api/v1/clubs", goodPayload);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        long failedCount = jdbc.queryForObject(
                "SELECT count(*) FROM t_mutation_audit_event "
                        + "WHERE tenant_club_id = ?::uuid AND failed = true",
                Long.class,
                SYSADMIN_TENANT.toString());
        assertThat(failedCount).isZero();

        long successCount = jdbc.queryForObject(
                "SELECT count(*) FROM t_mutation_audit_event "
                        + "WHERE tenant_club_id = ?::uuid AND failed = false AND action = 'CREATE'",
                Long.class,
                SYSADMIN_TENANT.toString());
        assertThat(successCount).isEqualTo(1L);
    }

    @Test
    void get_request_emits_no_failure_row_even_on_404() {
        ResponseEntity<String> res = get("/api/v1/clubs/clb-00000000-0000-0000-0000-000000000000");
        // 400 (malformed external form) or 404 — either way it's a GET so no audit.
        assertThat(res.getStatusCode().is2xxSuccessful()).isFalse();

        long rows = jdbc.queryForObject(
                "SELECT count(*) FROM t_mutation_audit_event WHERE tenant_club_id = ?::uuid",
                Long.class,
                SYSADMIN_TENANT.toString());
        assertThat(rows).isZero();
    }

    @Test
    void auth_failure_does_not_emit_mutation_event() {
        // No Authorization header → Spring Security 401. Auth events are
        // Actuator's surface (S-020 already feeds them via
        // Authentication{Success,Failure}Event); the mutation-audit trail
        // must not duplicate them. Story design pin under
        // §"Auth events stay in Actuator".
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "AuthFail");
        payload.put("slug", "auth-fail-" + suffix());
        payload.put("clubKey", "AFL" + shortSuffix());
        payload.put("publicRegistrationEnabled", false);
        payload.put("countryId", SEED_COUNTRY_ID);
        payload.put("clubStateId", SEED_CLUB_STATE_ID);

        ResponseEntity<String> res = rest.exchange(
                RequestEntity.post(URI.create("/api/v1/clubs"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload),
                String.class);
        assertThat(res.getStatusCode().value()).isIn(401, 403);

        // No row landed on the sysadmin tenant — the filter recognised
        // 401/403 as an auth surface and stayed out of the mutation trail.
        // (setUp truncated this tenant; if a row appears it can only be
        // from this request.)
        long onSysadmin = jdbc.queryForObject(
                "SELECT count(*) FROM t_mutation_audit_event WHERE tenant_club_id = ?::uuid",
                Long.class, SYSADMIN_TENANT.toString());
        assertThat(onSysadmin)
                .as("Auth-failure 401/403 must NOT emit a row into mutation_audit_event")
                .isZero();
    }

    @Test
    void failure_row_carries_request_id_from_inbound_header() {
        String correlation = "fail-trace-" + suffix();
        Map<String, Object> badPayload = new LinkedHashMap<>();
        badPayload.put("name", "");
        badPayload.put("slug", "ridfail-" + suffix());
        badPayload.put("clubKey", "RFL" + shortSuffix());
        badPayload.put("publicRegistrationEnabled", false);
        badPayload.put("countryId", SEED_COUNTRY_ID);
        badPayload.put("clubStateId", SEED_CLUB_STATE_ID);

        ResponseEntity<String> res = rest.exchange(authed(
                        RequestEntity.post(URI.create("/api/v1/clubs"))
                                .header("X-Request-Id", correlation)
                                .contentType(MediaType.APPLICATION_JSON))
                        .body(badPayload),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM t_mutation_audit_event "
                        + "WHERE tenant_club_id = ?::uuid AND failed = true",
                SYSADMIN_TENANT.toString());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("request_id")).isEqualTo(correlation);
    }

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

    private <T extends RequestEntity.BodyBuilder> T authed(T builder) {
        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + sysadminToken);
        return builder;
    }

    private RequestEntity.HeadersBuilder<?> authed(RequestEntity.HeadersBuilder<?> builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + sysadminToken);
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static String shortSuffix() {
        String s = Long.toString(System.nanoTime(), 36).toUpperCase(java.util.Locale.ROOT);
        return s.length() > 6 ? s.substring(s.length() - 6) : s;
    }
}
