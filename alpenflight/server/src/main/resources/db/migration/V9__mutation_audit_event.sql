-- =============================================================================
-- S-027: mutation_audit_event — single-table audit trail for every successful
-- (and every observed-failed) mutating endpoint.
--
-- Naming: `mutation_audit_event` (not `audit_event`) so Spring Boot Actuator's
-- own `AuditEvent` / `AuditEventRepository` (auth events) cannot collide. The
-- Java pin is `MutationAuditEvent` / `MutationAuditEventRepository`.
--
-- Per ADR 0022 directive 2: structural columns only. The `action` enum is
-- pinned in Java (`AuditAction` + `@Enumerated(STRING)`) — no Postgres CHECK
-- IN-set, no DB enum type. The "what changed" diff is S-056's UI concern;
-- `before_state` / `after_state` hold full jsonb snapshots.
--
-- Per-row tenancy: `tenant_club_id` is the operating tenant of the row being
-- written (the tenant that owns the mutation), not the actor's home tenant.
-- The Hibernate `@TenantId` on the entity field populates it from the
-- resolver — exactly the same path every other tenant-scoped row uses.
-- NULLABLE for the (currently unused) cross-tenant system-event case
-- (tenant-creation itself, cluster-wide config); reads via S-023
-- `UnscopedTenantContext` only.
--
-- GDPR / FADP: `actor_user_id ON DELETE SET NULL` lets right-to-erasure delete
-- the `user` row without orphaning audit history. PII inside the jsonb
-- snapshots is scrubbed by a separate future erasure job; S-027 commits only
-- to the FK action + the `[redacted]` default-deny serializer.
-- =============================================================================

CREATE TABLE mutation_audit_event (
    id                  UUID         NOT NULL PRIMARY KEY,
    occurred_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor_user_id       UUID,
    actor_keycloak_sub  TEXT,
    tenant_club_id      UUID,
    action              VARCHAR(32)  NOT NULL,
    target_entity_type  VARCHAR(64)  NOT NULL,
    target_entity_id    UUID,
    request_id          VARCHAR(64),
    before_state        JSONB,
    after_state         JSONB,
    failed              BOOLEAN      NOT NULL DEFAULT FALSE,
    system_actor        BOOLEAN      NOT NULL DEFAULT FALSE,
    http_status         SMALLINT,
    failure_reason      TEXT,
    CONSTRAINT fk_mutation_audit_event_actor_user_id
        FOREIGN KEY (actor_user_id) REFERENCES t_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_mutation_audit_event_tenant_club_id
        FOREIGN KEY (tenant_club_id) REFERENCES club (id) ON DELETE CASCADE
);

COMMENT ON COLUMN mutation_audit_event.tenant_club_id IS
    'Per-row tenancy: the operating tenant of the audited mutation. @TenantId discriminator.'
    ' NULL only for true cross-tenant system events (tenant-creation etc.); readable via S-023.'
    ' ON DELETE CASCADE — when a tenant is offboarded (hard club delete), its audit history goes'
    ' with it; same lifecycle attachment as the tenant''s domain rows.';
COMMENT ON COLUMN mutation_audit_event.actor_user_id IS
    'Internal user.id for the JWT subject. ON DELETE SET NULL so GDPR/FADP erasure of the user row'
    ' does not orphan the audit history. PII inside before_state/after_state is scrubbed by a separate erasure job.';
COMMENT ON COLUMN mutation_audit_event.actor_keycloak_sub IS
    'Immutable forensic key — raw JWT subject string. Survives actor_user_id being nulled by erasure.'
    ' TEXT (not UUID) because federated IdPs (Google numeric, Auth0 custom) hand us non-UUID subjects;'
    ' the audit trail records the principal''s identity verbatim and lets S-056 / forensics handle parsing.';
COMMENT ON COLUMN mutation_audit_event.action IS
    'AuditAction enum (CREATE, UPDATE, DELETE, STATE_TRANSITION, BULK_IMPORT). Pinned in Java per ADR 0022 directive 2.';

-- Per Performance plan: every list query is tenant-prefixed.
CREATE INDEX ix_mutation_audit_event_tenant_time
    ON mutation_audit_event (tenant_club_id, occurred_at DESC);
CREATE INDEX ix_mutation_audit_event_tenant_target
    ON mutation_audit_event (tenant_club_id, target_entity_type, occurred_at DESC);
CREATE INDEX ix_mutation_audit_event_tenant_actor
    ON mutation_audit_event (tenant_club_id, actor_user_id, occurred_at DESC);
CREATE INDEX ix_mutation_audit_event_request_id
    ON mutation_audit_event (request_id)
    WHERE request_id IS NOT NULL;

-- NOTE: Defense-in-depth append-only via DB-role grant (refinement threat-
-- model row d) is intentionally NOT shipped in this migration. The
-- forbidden-migration-patterns list bans GRANT/REVOKE in migrations
-- (current ops topology runs migrations + app on the same role; revoking
-- privileges from that role is destructive). Splitting the migration
-- role from the app role is tracked in S-160 (split migration + app DB
-- roles; grant audit table INSERT,SELECT-only to the app role).
