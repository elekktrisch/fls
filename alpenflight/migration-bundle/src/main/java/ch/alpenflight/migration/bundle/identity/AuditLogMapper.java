package ch.alpenflight.migration.bundle.identity;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.ParityIgnore;
import ch.alpenflight.migration.bundle.ParitySentinel;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

/**
 * Legacy audit-log porting: legacy {@code AuditLogs.AuditLogId BIGINT
 * IDENTITY} → {@code t_mutation_audit_event.id UUID} (V18 carries the
 * forensic-preservation triple {@code legacy_actor_user_id} +
 * {@code legacy_int_id} + {@code legacy_target_record_id}).
 *
 * <p>Lives under {@code identity.*} per the group-routing convention —
 * {@link EntityType#AUDIT_LOG} is grouped with IDENTITY because audit
 * actors are users.
 *
 * <p><strong>Actor resolution (producer-side).</strong>
 * Legacy {@code AuditLogs.UserName NVARCHAR NULL} is the actor (text,
 * not UUID FK). The producer emits two mutually-exclusive UUID
 * columns:
 * <ol>
 *   <li>NULL / whitespace-only UserName → both {@code actor_user_id}
 *       and {@code legacy_orphan_actor_id} NULL; {@code legacy_actor_user_id}
 *       NULL too (no synthesis — attributing system writes to one
 *       fake principal is forensically misleading).</li>
 *   <li>UserName looked up against the bundle's {@code Users.UserName}
 *       set (USER precedes AUDIT_LOG in {@link EntityType} ordering).
 *       Hit → {@code actor_user_id} carries the real {@code User.id};
 *       {@code legacy_orphan_actor_id} stays NULL.</li>
 *   <li>Miss → producer synthesises one UUID v7 per <em>distinct</em>
 *       legacy UserName (bundle-local {@code ON COMMIT DROP} cache)
 *       and emits {@code migration_run.warnings.AUDIT_ORPHAN_ACTOR}.
 *       The synthesised UUID lands in {@code legacy_orphan_actor_id}
 *       (V18 — no FK to {@code t_user} so the synthesized actor needs
 *       no Keycloak counterpart per ADR 0007); {@code actor_user_id}
 *       stays NULL. Legacy {@code LEGACY_SYSTEM_USER_ID = 13731ee2-…}
 *       routes through this path too.</li>
 *   <li>Cross-club UserName ambiguity (same UserName in two different
 *       Club.UserName sub-maps) → producer hard-fail
 *       ({@code AUDIT_USERNAME_AMBIGUOUS}).</li>
 * </ol>
 * On every non-NULL-UserName row {@code legacy_actor_user_id} carries
 * the raw text for forensic recall.
 *
 * <p><strong>EventType → action mapping.</strong>
 * {@code Added → CREATE}, {@code Modified → UPDATE},
 * {@code Deleted → DELETE}, {@code SoftDeleted → UPDATE},
 * {@code UnDeleted → UPDATE} (operator decision 2026-05-30: cleaner
 * enum semantics; SoftDeleted/UnDeleted are column-level mutations on
 * {@code deleted_on}; forensic-intent distinction is lossy but the
 * {@code target_entity_type} + {@code legacy_int_id} round-trip
 * preserve a full legacy-side query path).
 *
 * <p><strong>TypeFullName → target_entity_type.</strong> Producer
 * strips the {@code FLS.Server.Data.DbEntities.} namespace prefix
 * (mirrors {@code MappingExtensions.cs:628}); short names > 64 chars
 * after strip → producer reject ({@code AUDIT_TYPE_NAME_TOO_LONG}).
 *
 * <p><strong>RecordId → target_entity_id / legacy_target_record_id.</strong>
 * Producer attempts {@code UUID.fromString}; success → populated
 * {@code target_entity_id} + NULL {@code legacy_target_record_id}.
 * Failure → NULL {@code target_entity_id} + raw text into
 * {@code legacy_target_record_id} + {@code AUDIT_TARGET_NOT_UUID}
 * warning. Cross-entity remap via {@code legacy_id_map_<entity>}
 * is out of scope for S-186 — verbatim legacy UUID preserves
 * forensic linkage.
 *
 * <p><strong>tenant_club_id all-NULL on LEGACY_MIGRATED rows.</strong>
 * Legacy has no ClubId. Cross-tenant system-event semantics —
 * visible only via S-023 {@code UnscopedTenantContext} (SYSADMIN).
 * S-189 post-cutover follow-up back-fills per-tenant visibility on
 * demand.
 *
 * <p><strong>before_state / after_state NULL on migrated rows.</strong>
 * Legacy {@code AuditLogDetails} is dropped (manifest WHY-not-mapped).
 * Accepted parity exclusion; S-187 oracle treats both as
 * {@link ParityIgnore} for LEGACY_MIGRATED rows.
 *
 * <p>{@code actor_user_id} → cross-tenant ride-through to historical
 * {@code User} rows; declared in
 * {@link ch.alpenflight.migration.bundle.Manifest}'s
 * {@code TENANT_BYPASS_ALLOW_LIST} via the S-186 widening.
 *
 * <p>The mapper's {@code legacy_guid} column carries a per-row UUID v7
 * minted producer-side from the legacy {@code AuditLogId BIGINT} —
 * AuditLogs is the only legacy table whose PK is BIGINT IDENTITY
 * rather than {@code uniqueidentifier}, so there is no legacy GUID to
 * carry through. The forensic key is {@code legacy_int_id BIGINT}
 * (V18) preserved verbatim.
 */
public final class AuditLogMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";
    static final String OCCURRED_AT = "occurred_at";
    static final String ACTOR_USER_ID = "actor_user_id";

    @ParityIgnore
    static final String ACTOR_KEYCLOAK_SUB = "actor_keycloak_sub";

    @ParityIgnore
    static final String TENANT_CLUB_ID = "tenant_club_id";

    @ParitySentinel
    static final String ACTION = "action";

    @ParitySentinel
    static final String ACTOR_KIND = "actor_kind";

    static final String TARGET_ENTITY_TYPE = "target_entity_type";
    static final String TARGET_ENTITY_ID = "target_entity_id";

    @ParityIgnore
    static final String REQUEST_ID = "request_id";

    @ParityIgnore
    static final String BEFORE_STATE = "before_state";

    @ParityIgnore
    static final String AFTER_STATE = "after_state";

    static final String FAILED = "failed";
    static final String SYSTEM_ACTOR = "system_actor";

    @ParityIgnore
    static final String HTTP_STATUS = "http_status";

    @ParityIgnore
    static final String FAILURE_REASON = "failure_reason";

    static final String LEGACY_ACTOR_USER_ID = "legacy_actor_user_id";

    @ParitySentinel
    static final String LEGACY_INT_ID = "legacy_int_id";

    static final String LEGACY_TARGET_RECORD_ID = "legacy_target_record_id";

    static final String LEGACY_ORPHAN_ACTOR_ID = "legacy_orphan_actor_id";

    private static final String[] COLUMNS = {
            LEGACY_GUID, OCCURRED_AT,
            ACTOR_USER_ID, ACTOR_KEYCLOAK_SUB, TENANT_CLUB_ID,
            ACTION, ACTOR_KIND,
            TARGET_ENTITY_TYPE, TARGET_ENTITY_ID,
            REQUEST_ID, BEFORE_STATE, AFTER_STATE,
            FAILED, SYSTEM_ACTOR, HTTP_STATUS, FAILURE_REASON,
            LEGACY_ACTOR_USER_ID, LEGACY_INT_ID, LEGACY_TARGET_RECORD_ID,
            LEGACY_ORPHAN_ACTOR_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.AUDIT_LOG;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        // actor_user_id → USER (cross-tenant ride-through via Manifest
        // bypass). tenant_club_id stays NULL on migrated rows — not an
        // ingest-time FK. target_entity_id has no FK in V9.
        return List.of(EntityType.USER);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("LegacyGuid"));
        Coercions.writeRequiredTimestamp(target, OCCURRED_AT,
                source.getTimestamp("EventDateUTC"));
        Coercions.writeOptionalString(target, ACTOR_USER_ID,
                source.getString("ResolvedActorUserId"));
        target.writeNullField(ACTOR_KEYCLOAK_SUB);
        target.writeNullField(TENANT_CLUB_ID);
        target.writeStringField(ACTION, source.getString("ResolvedAction"));
        target.writeStringField(ACTOR_KIND, "LEGACY_MIGRATED");
        target.writeStringField(TARGET_ENTITY_TYPE,
                source.getString("ResolvedTargetEntityType"));
        Coercions.writeOptionalString(target, TARGET_ENTITY_ID,
                source.getString("ResolvedTargetEntityId"));
        target.writeNullField(REQUEST_ID);
        target.writeNullField(BEFORE_STATE);
        target.writeNullField(AFTER_STATE);
        target.writeBooleanField(FAILED, false);
        target.writeBooleanField(SYSTEM_ACTOR, false);
        target.writeNullField(HTTP_STATUS);
        target.writeNullField(FAILURE_REASON);
        Coercions.writeOptionalString(target, LEGACY_ACTOR_USER_ID,
                source.getString("UserName"));
        target.writeNumberField(LEGACY_INT_ID, source.getLong("AuditLogId"));
        Coercions.writeOptionalString(target, LEGACY_TARGET_RECORD_ID,
                source.getString("ResolvedLegacyTargetRecordId"));
        Coercions.writeOptionalString(target, LEGACY_ORPHAN_ACTOR_ID,
                source.getString("ResolvedLegacyOrphanActorId"));
        target.writeEndObject();
    }

    @Override
    public void readEntity(JsonNode source, PreparedStatement target) throws SQLException {
        int position = 1;
        target.setObject(position++, UUID.fromString(source.get(LEGACY_GUID).asText()));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, OCCURRED_AT));
        target.setObject(position++, Coercions.readUuidOrNull(source, ACTOR_USER_ID));
        target.setString(position++, Coercions.readStringOrNull(source, ACTOR_KEYCLOAK_SUB));
        target.setObject(position++, Coercions.readUuidOrNull(source, TENANT_CLUB_ID));
        target.setString(position++, source.get(ACTION).asText());
        target.setString(position++, source.get(ACTOR_KIND).asText());
        target.setString(position++, source.get(TARGET_ENTITY_TYPE).asText());
        target.setObject(position++, Coercions.readUuidOrNull(source, TARGET_ENTITY_ID));
        target.setString(position++, Coercions.readStringOrNull(source, REQUEST_ID));
        // before_state / after_state are jsonb — bind via Types.OTHER even
        // though we always emit NULL on migrated rows (S-141's PreparedStatement
        // sees the same shape regardless of value).
        target.setNull(position++, Types.OTHER);
        target.setNull(position++, Types.OTHER);
        target.setObject(position++, source.get(FAILED).asBoolean());
        target.setObject(position++, source.get(SYSTEM_ACTOR).asBoolean());
        Coercions.bindShortOrNull(target, position++, source, HTTP_STATUS);
        target.setString(position++, Coercions.readStringOrNull(source, FAILURE_REASON));
        target.setString(position++, Coercions.readStringOrNull(source, LEGACY_ACTOR_USER_ID));
        target.setLong(position++, source.get(LEGACY_INT_ID).longValue());
        target.setString(position++,
                Coercions.readStringOrNull(source, LEGACY_TARGET_RECORD_ID));
        target.setObject(position, Coercions.readUuidOrNull(source, LEGACY_ORPHAN_ACTOR_ID));
    }
}
