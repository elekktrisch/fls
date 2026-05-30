package ch.alpenflight.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reads back a LEGACY_MIGRATED {@code t_mutation_audit_event} row written
 * by S-186's {@code AuditLogMapper}. The migrated rows land with
 * {@code tenant_club_id IS NULL} (cross-tenant system-event semantics per
 * the operator-grilled refinement decision) — so {@code @TenantId} filter
 * resolves them only through S-023's {@code UnscopedTenantContext}, which
 * isn't shipped yet. This test verifies the persistence layer directly via
 * {@link JdbcTemplate}, asserting the V18 schema additions accept the
 * mapper's bind shape end-to-end.
 *
 * <p>Once S-023 lands and {@code UnscopedTenantContext.runAs(...)} can
 * surface NULL-tenant rows, this test (or a sibling) will additionally
 * cover the {@code MutationAuditEventRepository.findById} round-trip
 * under the unscoped context.
 */
class MutationAuditEventLegacyMigratedReadBackIT extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void legacyMigratedRowSurfacesActorKindLegacyActorUserIdAndForensicTriple() {
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
                        + "legacy_actor_user_id, legacy_int_id, legacy_target_record_id) "
                        + "VALUES (?, ?, ?, NULL, NULL, ?, ?, NULL, NULL, NULL, NULL, false, "
                        + "false, NULL, NULL, ?, ?, ?, ?)",
                rowId, java.sql.Timestamp.from(occurredAt), synthesisedOrphanActorId,
                "UPDATE", "Flight",
                "LEGACY_MIGRATED", "j.doe", legacyAuditLogIdentity, "42");

        var row = jdbc.queryForMap(
                "SELECT actor_kind, actor_user_id, actor_keycloak_sub, tenant_club_id, "
                        + "       action, legacy_actor_user_id, legacy_int_id, "
                        + "       legacy_target_record_id, before_state, after_state "
                        + "  FROM t_mutation_audit_event "
                        + " WHERE id = ?", rowId);

        assertThat(row.get("actor_kind"))
                .as("V18 actor_kind discriminator must persist verbatim as the Java enum string")
                .isEqualTo("LEGACY_MIGRATED");
        assertThat(row.get("actor_user_id"))
                .as("Synthetic orphan UUID v7 minted by the producer survives round-trip")
                .isEqualTo(synthesisedOrphanActorId);
        assertThat(row.get("actor_keycloak_sub"))
                .as("Legacy actors have no Keycloak counterpart per ADR 0007")
                .isNull();
        assertThat(row.get("tenant_club_id"))
                .as("Legacy AuditLogs has no ClubId column — tenant_club_id NULL on migrated rows; "
                        + "S-189 follow-up back-fills post-cutover")
                .isNull();
        assertThat(row.get("action"))
                .as("EventType → action mapping per operator decision 2026-05-30")
                .isEqualTo("UPDATE");
        assertThat(row.get("legacy_actor_user_id"))
                .as("Forensic preservation — raw legacy UserName")
                .isEqualTo("j.doe");
        assertThat(((Number) row.get("legacy_int_id")).longValue())
                .as("Forensic round-trip key — legacy AuditLogs.AuditLogId IDENTITY")
                .isEqualTo(legacyAuditLogIdentity);
        assertThat(row.get("legacy_target_record_id"))
                .as("Raw RecordId text for the non-UUID-parseable case")
                .isEqualTo("42");
        assertThat(row.get("before_state"))
                .as("AuditLogDetails dropped → no property-change snapshot")
                .isNull();
        assertThat(row.get("after_state")).isNull();

        jdbc.update("DELETE FROM t_mutation_audit_event WHERE id = ?", rowId);
    }
}
