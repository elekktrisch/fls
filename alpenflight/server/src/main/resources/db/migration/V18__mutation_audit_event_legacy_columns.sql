
ALTER TABLE t_mutation_audit_event
    ADD COLUMN actor_kind               VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    ADD COLUMN legacy_actor_user_id     TEXT,
    ADD COLUMN legacy_int_id            BIGINT,
    ADD COLUMN legacy_target_record_id  TEXT,
    ADD COLUMN legacy_orphan_actor_id   UUID;

COMMENT ON COLUMN t_mutation_audit_event.actor_kind IS
    'AuditActorKind enum (NORMAL, SYSTEM, LEGACY_MIGRATED). Pinned in Java per'
    ' ADR 0022 D2 — no CHECK IN-set, no DB enum. LEGACY_MIGRATED is the only'
    ' value S-186 writes on cutover; new mutating endpoints continue to write'
    ' NORMAL via MutationAuditEventListener.';
COMMENT ON COLUMN t_mutation_audit_event.legacy_actor_user_id IS
    'Raw legacy AuditLogs.UserName text for LEGACY_MIGRATED rows. PII —'
    ' @AuditRedact + S-027 default-deny serializer + AuditPayloadTurboFilter'
    ' cover it. NULL on actor_kind <> LEGACY_MIGRATED.';
COMMENT ON COLUMN t_mutation_audit_event.legacy_int_id IS
    'Legacy AuditLogs.AuditLogId IDENTITY (BIGINT) for LEGACY_MIGRATED rows.'
    ' Forensic round-trip key into the legacy DB; not PII. NULL on actor_kind'
    ' <> LEGACY_MIGRATED.';
COMMENT ON COLUMN t_mutation_audit_event.legacy_target_record_id IS
    'Raw legacy AuditLogs.RecordId text when not UUID-parseable (legacy rows'
    ' whose target had a BIGINT identity rather than a UUID). NULL when'
    ' target_entity_id is populated. PII-adjacent (entity IDs in URLs) —'
    ' @AuditRedact covers it.';
COMMENT ON COLUMN t_mutation_audit_event.legacy_orphan_actor_id IS
    'Synthetic UUID v7 minted per distinct legacy AuditLogs.UserName when no'
    ' matching Users row exists in the bundle. No FK — the synthesized actor'
    ' has no Keycloak counterpart per ADR 0007. actor_user_id is NULL on'
    ' every row carrying a legacy_orphan_actor_id; the pair signals the'
    ' orphan path.';
