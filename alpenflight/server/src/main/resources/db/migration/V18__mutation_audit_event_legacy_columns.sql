-- S-186 — extends t_mutation_audit_event for the legacy audit-log migration.
-- S-141 ingest writes the new columns when S-186's AuditLogMapper.readEntity
-- binds them; new mutating endpoints continue to write actor_kind = 'NORMAL'
-- via MutationAuditEventListener and leave the legacy_* columns NULL.
--
-- Schema-deviation review (ADR 0022 directive 2): four columns, no CHECK
-- constraints, no generated columns, no triggers. actor_kind is the Java enum
-- AuditActorKind ({NORMAL, SYSTEM, LEGACY_MIGRATED}) bound via @Enumerated
-- (STRING) — no CHECK IN-set per ADR 0022 D2 (same pattern as action).
--
-- Forensic preservation triple — legacy_int_id (AuditLogs.AuditLogId),
-- legacy_actor_user_id (AuditLogs.UserName), legacy_target_record_id
-- (AuditLogs.RecordId when not UUID-parseable). Together they let a forensic
-- query trace any migrated audit row back to the legacy DB without a
-- round-trip through legacy_id_map_<entity>.
--
-- legacy_orphan_actor_id holds the per-distinct-UserName synthetic UUID v7
-- for orphan actors (UserName text with no matching Users.UserName row in
-- the bundle). Intentionally NO FK to t_user: the synthesized UUID never
-- corresponds to a real Keycloak-backed principal (ADR 0007 forbids the
-- shadow), so adding it to t_user would break the single-writer guard and
-- the FK gate would reject the row. actor_user_id is NULL on every
-- orphan-actor row; the (actor_user_id NULL, legacy_orphan_actor_id NOT
-- NULL, legacy_actor_user_id NOT NULL) triple is the wire-shape signal.

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
