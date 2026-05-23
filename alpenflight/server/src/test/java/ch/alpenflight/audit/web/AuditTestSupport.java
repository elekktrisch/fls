package ch.alpenflight.audit.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Helpers for ITs that assert against {@code mutation_audit_event} rows.
 *
 * <p>The audit listener commits in a REQUIRES_NEW transaction, so a
 * {@code @Transactional} test rollback cannot clean those rows up. Tests
 * own their data by tenant key + delete the rows they planted (per
 * {@link #truncateForTenant}) at start.
 *
 * <p>Reads bypass JPA via {@link JdbcTemplate} — Hibernate's
 * {@code @TenantId} filter would otherwise scope away rows the test wants
 * to inspect across tenants.
 */
final class AuditTestSupport {

    static final ObjectMapper JSON = new ObjectMapper();

    private AuditTestSupport() {}

    /** Delete every audit row for {@code tenantClubId}. Run as a per-test pre-clean. */
    static void truncateForTenant(JdbcTemplate jdbc, UUID tenantClubId) {
        jdbc.update("DELETE FROM mutation_audit_event WHERE tenant_club_id = ?::uuid",
                tenantClubId.toString());
    }

    /** Return every audit row for {@code tenantClubId}, ordered by occurred_at. */
    static List<Map<String, Object>> findByTenant(JdbcTemplate jdbc, UUID tenantClubId) {
        return jdbc.queryForList(
                "SELECT * FROM mutation_audit_event WHERE tenant_club_id = ?::uuid "
                        + "ORDER BY occurred_at ASC",
                tenantClubId.toString());
    }

    /**
     * Return audit rows for {@code tenantClubId} filtered to a single target
     * id — the typical "did POST X produce its event" probe.
     */
    static List<Map<String, Object>> findByTarget(JdbcTemplate jdbc,
                                                  UUID tenantClubId,
                                                  UUID targetEntityId) {
        return jdbc.queryForList(
                "SELECT * FROM mutation_audit_event "
                        + "WHERE tenant_club_id = ?::uuid AND target_entity_id = ?::uuid "
                        + "ORDER BY occurred_at ASC",
                tenantClubId.toString(),
                targetEntityId.toString());
    }

    /** Parse the {@code before_state} / {@code after_state} jsonb column. */
    static JsonNode parseSnapshot(Object jsonbColumnValue) {
        if (jsonbColumnValue == null) {
            return JSON.nullNode();
        }
        try {
            return JSON.readTree(jsonbColumnValue.toString());
        } catch (Exception e) {
            throw new AssertionError("audit row snapshot is not valid JSON: " + jsonbColumnValue, e);
        }
    }

    /** Convenience: assert exactly one audit row for the target and return it. */
    static Map<String, Object> assertSingleEventForTarget(JdbcTemplate jdbc,
                                                          UUID tenantClubId,
                                                          UUID targetEntityId) {
        List<Map<String, Object>> rows = findByTarget(jdbc, tenantClubId, targetEntityId);
        assertThat(rows)
                .as("Expected exactly one audit row for tenant=%s target=%s but got: %s",
                        tenantClubId, targetEntityId, rows)
                .hasSize(1);
        return rows.get(0);
    }
}
