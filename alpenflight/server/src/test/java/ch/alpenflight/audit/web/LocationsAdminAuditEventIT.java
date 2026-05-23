package ch.alpenflight.audit.web;

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
import java.util.Locale;
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
 * Pins per-row tenancy for sysadmin cross-tenant admin paths
 * ({@code LocationsAdminController}). The path-variable {@code clubId}
 * is the audit-row tenant, not the sysadmin's home tenant — even on the
 * synthetic-failure path, where {@code Tenants.runAs} has already
 * unwound by the time the filter records the failure.
 *
 * <p>{@link ch.alpenflight.platform.tenancy.RequestTenantHint} is the
 * mechanism: {@code runAs} publishes the target tenant as a request
 * attribute that survives the unwind; the filter consults it and runs
 * its {@code recordFailed} inside {@code runAs(hint, ...)} so the
 * row's {@code @TenantId} discriminator binds the right club.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class LocationsAdminAuditEventIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID SYSADMIN_TENANT = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final String SEED_COUNTRY_ID = "019e2e15-2c00-74be-8000-0000000004be";
    private static final String SEED_CLUB_STATE_ID = "019e2e15-2c00-7bb8-8000-000000000bb8";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    private String sysadminToken;
    private UUID targetTenant;

    @BeforeEach
    void setUp() throws Exception {
        sysadminToken = jwts.mint(c -> c
                .claim("clubId", SYSADMIN_TENANT.toString())
                .claim("realm_access", Map.of("roles", List.of("SYSTEM_ADMINISTRATOR"))));

        targetTenant = createTargetClub();

        // Both tenants accumulate rows during setup (create-target POST audits
        // under the sysadmin tenant; clubs-controller emits its own success
        // row). Reset.
        truncateForTenant(jdbc, SYSADMIN_TENANT);
        truncateForTenant(jdbc, targetTenant);
    }

    @Test
    void admin_failed_400_validation_emits_failed_row_on_target_tenant_not_sysadmin_home() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("locationName", "");  // @NotBlank — bean validation 400.
        bad.put("isInboundRouteRequired", false);
        bad.put("isOutboundRouteRequired", false);
        bad.put("isFastEntryRecord", false);

        ResponseEntity<String> res = rest.exchange(
                authed(RequestEntity.post(URI.create("/api/v1/admin/locations/clb-" + targetTenant))
                        .contentType(MediaType.APPLICATION_JSON))
                        .body(bad),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        List<Map<String, Object>> onTarget = jdbc.queryForList(
                "SELECT * FROM mutation_audit_event "
                        + "WHERE tenant_club_id = ?::uuid AND failed = true",
                targetTenant.toString());
        List<Map<String, Object>> onSysadmin = jdbc.queryForList(
                "SELECT * FROM mutation_audit_event "
                        + "WHERE tenant_club_id = ?::uuid AND failed = true",
                SYSADMIN_TENANT.toString());

        assertThat(onTarget)
                .as("Synthetic failure must land on the path-variable target tenant — "
                        + "AuditTargetTenantInterceptor publishes the hint before validation")
                .hasSize(1);
        assertThat(onSysadmin)
                .as("Synthetic failure must NOT land on the sysadmin's home tenant")
                .isEmpty();
    }

    private UUID createTargetClub() throws Exception {
        String slug = "admin-audit-tgt-" + Long.toString(System.nanoTime(), 36);
        String key = "AAT" + Long.toString(System.nanoTime(), 36).toUpperCase(Locale.ROOT).substring(0, 6);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "AdminAuditTarget");
        payload.put("slug", slug);
        payload.put("clubKey", key);
        payload.put("publicRegistrationEnabled", false);
        payload.put("countryId", SEED_COUNTRY_ID);
        payload.put("clubStateId", SEED_CLUB_STATE_ID);

        ResponseEntity<String> res = rest.exchange(
                authed(RequestEntity.post(URI.create("/api/v1/clubs"))
                        .contentType(MediaType.APPLICATION_JSON))
                        .body(payload),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = JSON.readTree(res.getBody());
        return ClubId.parse(body.get("id").asText()).value();
    }

    private <T extends RequestEntity.BodyBuilder> T authed(T builder) {
        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + sysadminToken);
        return builder;
    }
}
