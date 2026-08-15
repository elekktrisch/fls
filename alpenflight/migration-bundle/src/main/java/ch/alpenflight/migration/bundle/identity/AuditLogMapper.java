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

    private static final int JSONB_SQL_TYPE = Types.OTHER;

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
    public String[] wireColumns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeyTargets() {
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
        target.setNull(position++, JSONB_SQL_TYPE);
        target.setNull(position++, JSONB_SQL_TYPE);
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
