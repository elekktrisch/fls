package ch.alpenflight.migration.bundle.identity;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class AuditLogMapperTest extends AbstractMapperContractTest<AuditLogMapper> {

    private final AuditLogMapper mapper = new AuditLogMapper();

    @Override
    protected AuditLogMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        // Round-trip fixture populates BOTH mutually-exclusive UUID columns
        // (real User match + synthetic orphan) so the abstract suite's
        // every-position-bound check passes. At-most-one invariants live on
        // the audit aggregate; producer enforces them at write time.
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("LegacyGuid", randomUuidString(faker));
        row.put("EventDateUTC", Timestamp.from(Instant.parse("2024-06-15T08:30:00Z")));
        row.put("ResolvedActorUserId", randomUuidString(faker));
        row.put("ResolvedAction", "UPDATE");
        row.put("ResolvedTargetEntityType", "Flight");
        row.put("ResolvedTargetEntityId", randomUuidString(faker));
        row.put("UserName", "j.doe");
        row.put("AuditLogId", 1_234_567L);
        row.put("ResolvedLegacyTargetRecordId", "12345");
        row.put("ResolvedLegacyOrphanActorId", randomUuidString(faker));
        return row;
    }

    private Map<String, Object> orphanActorRow(Faker faker) {
        Map<String, Object> row = legacyRow(faker);
        row.put("ResolvedActorUserId", null);
        return row;
    }

    private Map<String, Object> nullActorRow(Faker faker) {
        Map<String, Object> row = legacyRow(faker);
        row.put("ResolvedActorUserId", null);
        row.put("UserName", null);
        row.put("ResolvedLegacyOrphanActorId", null);
        return row;
    }

    @Test
    void exposesAuditLogEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.AUDIT_LOG);
    }

    @Test
    void declaresOnlyUserAsForeignKey() {
        // actor_user_id → cross-tenant USER via Manifest bypass.
        // target_entity_id has no FK constraint in V9; tenant_club_id
        // stays NULL on migrated rows.
        assertThat(mapper.foreignKeys()).containsExactly(EntityType.USER);
    }

    @Test
    void emitsLegacyMigratedActorKindOnEveryRow() throws Exception {
        JsonNode emitted = invokeWriteNdjson(mapper, legacyRow(seededFaker()));
        assertThat(emitted.get(AuditLogMapper.ACTOR_KIND).asText())
                .as("Every row this mapper emits is a LEGACY_MIGRATED audit row — "
                        + "new mutating endpoints continue to write actor_kind=NORMAL "
                        + "via MutationAuditEventListener and don't go through this mapper.")
                .isEqualTo("LEGACY_MIGRATED");
    }

    @Test
    void actorKeycloakSubAlwaysNullForLegacyMigratedRows() throws Exception {
        JsonNode emitted = invokeWriteNdjson(mapper, legacyRow(seededFaker()));
        assertThat(emitted.get(AuditLogMapper.ACTOR_KEYCLOAK_SUB).isNull())
                .as("Legacy AuditLogs have no Keycloak sub — actor_keycloak_sub "
                        + "must be NULL on every LEGACY_MIGRATED row per ADR 0007")
                .isTrue();
    }

    @Test
    void tenantClubIdAlwaysNullForLegacyMigratedRows() throws Exception {
        JsonNode emitted = invokeWriteNdjson(mapper, legacyRow(seededFaker()));
        assertThat(emitted.get(AuditLogMapper.TENANT_CLUB_ID).isNull())
                .as("Legacy AuditLogs has no ClubId column — tenant_club_id is NULL "
                        + "on migrated rows (cross-tenant system-event semantics). "
                        + "S-189 post-cutover back-fill resolves per-tenant visibility.")
                .isTrue();
    }

    @Test
    void legacyActorUserIdCarriesUserName() throws Exception {
        JsonNode emitted = invokeWriteNdjson(mapper, legacyRow(seededFaker()));
        assertThat(emitted.get(AuditLogMapper.LEGACY_ACTOR_USER_ID).asText())
                .as("Forensic preservation — raw legacy UserName text survives in "
                        + "legacy_actor_user_id even when actor_user_id resolves to "
                        + "a real or synthetic UUID")
                .isEqualTo("j.doe");
    }

    @Test
    void legacyIntIdCarriesAuditLogIdentity() throws Exception {
        JsonNode emitted = invokeWriteNdjson(mapper, legacyRow(seededFaker()));
        assertThat(emitted.get(AuditLogMapper.LEGACY_INT_ID).asLong())
                .as("Forensic round-trip key — AuditLogs.AuditLogId BIGINT IDENTITY "
                        + "preserved on every migrated row")
                .isEqualTo(1_234_567L);
    }

    @Test
    void beforeAndAfterStateAreAlwaysNullForLegacyMigratedRows() throws Exception {
        JsonNode emitted = invokeWriteNdjson(mapper, legacyRow(seededFaker()));
        assertThat(emitted.get(AuditLogMapper.BEFORE_STATE).isNull())
                .as("Legacy AuditLogDetails (property-change records) is dropped — "
                        + "before_state has no source. Accepted parity exclusion.")
                .isTrue();
        assertThat(emitted.get(AuditLogMapper.AFTER_STATE).isNull()).isTrue();
    }

    @Test
    void orphanRowEmitsLegacyOrphanActorIdAndNullActorUserId() throws Exception {
        JsonNode emitted = invokeWriteNdjson(mapper, orphanActorRow(seededFaker()));
        assertThat(emitted.get(AuditLogMapper.ACTOR_USER_ID).isNull())
                .as("Orphan actors land in legacy_orphan_actor_id (no FK), NOT in "
                        + "actor_user_id (which FK-constrains to t_user — synthesizing a "
                        + "UUID there would violate fk_mutation_audit_event_actor_user_id "
                        + "since the synthesized actor has no Keycloak counterpart "
                        + "per ADR 0007)")
                .isTrue();
        assertThat(emitted.get(AuditLogMapper.LEGACY_ORPHAN_ACTOR_ID).isNull())
                .as("Synthesized orphan UUID v7 must surface here")
                .isFalse();
        assertThat(emitted.get(AuditLogMapper.LEGACY_ACTOR_USER_ID).asText())
                .as("Forensic UserName text always survives on a non-NULL-UserName row")
                .isEqualTo("j.doe");
    }

    @Test
    void nullUserNameRowEmitsNullActorAndNoOrphanSynthesis() throws Exception {
        JsonNode emitted = invokeWriteNdjson(mapper, nullActorRow(seededFaker()));
        assertThat(emitted.get(AuditLogMapper.ACTOR_USER_ID).isNull())
                .as("NULL UserName → NULL actor_user_id")
                .isTrue();
        assertThat(emitted.get(AuditLogMapper.LEGACY_ORPHAN_ACTOR_ID).isNull())
                .as("NULL UserName must NOT synthesize an orphan UUID — attributing "
                        + "all NULL-UserName writes to one fake principal would bucket "
                        + "unrelated system events under a single misleading actor")
                .isTrue();
        assertThat(emitted.get(AuditLogMapper.LEGACY_ACTOR_USER_ID).isNull())
                .as("NULL UserName → NULL legacy_actor_user_id text (no forensic to preserve)")
                .isTrue();
    }
}
