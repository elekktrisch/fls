package ch.alpenflight.audit.web;

import static ch.alpenflight.audit.web.AuditTestSupport.truncateForTenant;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pins the {@code @TransactionalEventListener(AFTER_COMMIT)} +
 * {@code @Transactional(REQUIRES_NEW)} design.
 *
 * <p>Drives a test-only endpoint that publishes a success audit event
 * inside its {@code @Transactional} method and then throws. AFTER_COMMIT
 * is conditioned on the tx committing — so the success row must NOT land.
 * The {@link RequestAuditFilter} sees the 500 and emits one synthetic
 * {@code failed=true} row through its own REQUIRES_NEW listener path.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class AuditRollbackSemanticsIT extends PostgresIntegrationTest {

    private static final UUID SYSADMIN_TENANT = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

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
    void failed_500_inside_tx_rolled_back_still_emits_event() {
        UUID targetId = UUID.randomUUID();
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.post(URI.create("/api/v1/__test__/audit-rollback/" + targetId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sysadminToken)
                        .build(),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        // The success path published a MutationAuditRequested event inside the
        // rolled-back tx → AFTER_COMMIT listener never fires → no row for that
        // target lands.
        long successRows = jdbc.queryForObject(
                "SELECT count(*) FROM mutation_audit_event "
                        + "WHERE target_entity_id = ?::uuid AND failed = false",
                Long.class, targetId.toString());
        assertThat(successRows)
                .as("Success row must NOT land — AFTER_COMMIT only fires on commit")
                .isZero();

        // The filter's synthetic-failure path emits ONE row in its own
        // REQUIRES_NEW transaction, independent of the rolled-back outer tx.
        List<Map<String, Object>> failedRows = jdbc.queryForList(
                "SELECT * FROM mutation_audit_event "
                        + "WHERE tenant_club_id = ?::uuid AND failed = true",
                SYSADMIN_TENANT.toString());
        assertThat(failedRows)
                .as("Exactly one synthetic failure row from the 500 mid-tx")
                .hasSize(1);
        Map<String, Object> row = failedRows.get(0);
        assertThat(row.get("http_status")).isEqualTo(500);
        assertThat(row.get("action")).isEqualTo("CREATE");
    }
}
