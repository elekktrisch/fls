package ch.alpenflight.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class MutationAuditEventLegacyMigratedReadBackIT extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void orphanActorRowSurfacesForensicQuadrupleWithNullActorUserId() {
        UUID rowId = UUID.fromString("019e30c3-2c00-71a7-8000-000000000186");
        UUID synthesisedOrphanActorId = UUID.fromString("019e30c3-2c00-71a7-8000-00000000018a");
        Instant occurredAt = Instant.parse("2024-06-15T08:30:00Z");
        long legacyAuditLogIdentity = 1_234_567L;

        jdbc.update("DELETE FROM t_mutation_audit_event WHERE id = ?", rowId);
        jdbc.update(
                "INSERT INTO t_mutation_audit_event ("
                        + "id, occurred_at, actor_user_id, actor_keycloak_sub, tenant_club_id, "
                        + "action, target_entity_type, target_entity_id, "
                        + "request_id, before_state, after_state, failed, system_actor, "
                        + "http_status, failure_reason, actor_kind, "
                        + "legacy_actor_user_id, legacy_int_id, legacy_target_record_id, "
                        + "legacy_orphan_actor_id) "
                        + "VALUES (?, ?, NULL, NULL, NULL, ?, ?, NULL, NULL, NULL, NULL, false, "
                        + "false, NULL, NULL, ?, ?, ?, ?, ?)",
                rowId, java.sql.Timestamp.from(occurredAt),
                "UPDATE", "Flight",
                "LEGACY_MIGRATED", "j.doe", legacyAuditLogIdentity, "42",
                synthesisedOrphanActorId);

        var row = jdbc.queryForMap(
                "SELECT actor_kind, actor_user_id, actor_keycloak_sub, tenant_club_id, "
                        + "       action, legacy_actor_user_id, legacy_int_id, "
                        + "       legacy_target_record_id, legacy_orphan_actor_id, "
                        + "       before_state, after_state "
                        + "  FROM t_mutation_audit_event "
                        + " WHERE id = ?", rowId);

        assertThat(row.get("actor_kind")).isEqualTo("LEGACY_MIGRATED");
        assertThat(row.get("actor_user_id"))
                .as("actor_user_id is FK-constrained to t_user; orphan synthesis goes to "
                        + "legacy_orphan_actor_id (no FK) instead per the V18 split")
                .isNull();
        assertThat(row.get("legacy_orphan_actor_id"))
                .as("Synthetic UUID v7 lands here for forensic per-actor grouping")
                .isEqualTo(synthesisedOrphanActorId);
        assertThat(row.get("actor_keycloak_sub")).isNull();
        assertThat(row.get("tenant_club_id"))
                .as("Legacy AuditLogs has no ClubId — S-189 back-fills post-cutover")
                .isNull();
        assertThat(row.get("action")).isEqualTo("UPDATE");
        assertThat(row.get("legacy_actor_user_id")).isEqualTo("j.doe");
        assertThat(((Number) row.get("legacy_int_id")).longValue())
                .isEqualTo(legacyAuditLogIdentity);
        assertThat(row.get("legacy_target_record_id")).isEqualTo("42");
        assertThat(row.get("before_state")).isNull();
        assertThat(row.get("after_state")).isNull();

        jdbc.update("DELETE FROM t_mutation_audit_event WHERE id = ?", rowId);
    }
}
