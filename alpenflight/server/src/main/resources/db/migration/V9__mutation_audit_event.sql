
CREATE TABLE t_mutation_audit_event (
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
        FOREIGN KEY (tenant_club_id) REFERENCES t_club (id) ON DELETE CASCADE
);

COMMENT ON COLUMN t_mutation_audit_event.tenant_club_id IS
    'Per-row tenancy: the operating tenant of the audited mutation. @TenantId discriminator.'
    ' NULL only for true cross-tenant system events (tenant-creation etc.); readable via S-023.'
    ' ON DELETE CASCADE — when a tenant is offboarded (hard club delete), its audit history goes'
    ' with it; same lifecycle attachment as the tenant''s domain rows.';
COMMENT ON COLUMN t_mutation_audit_event.actor_user_id IS
    'Internal user.id for the JWT subject. ON DELETE SET NULL so GDPR/FADP erasure of the user row'
    ' does not orphan the audit history. PII inside before_state/after_state is scrubbed by a separate erasure job.';
COMMENT ON COLUMN t_mutation_audit_event.actor_keycloak_sub IS
    'Immutable forensic key — raw JWT subject string. Survives actor_user_id being nulled by erasure.'
    ' TEXT (not UUID) because federated IdPs (Google numeric, Auth0 custom) hand us non-UUID subjects;'
    ' the audit trail records the principal''s identity verbatim and lets S-056 / forensics handle parsing.';
COMMENT ON COLUMN t_mutation_audit_event.action IS
    'AuditAction enum (CREATE, UPDATE, DELETE, STATE_TRANSITION, BULK_IMPORT). Pinned in Java per ADR 0022 directive 2.';

CREATE INDEX ix_mutation_audit_event_tenant_time
    ON t_mutation_audit_event (tenant_club_id, occurred_at DESC);
CREATE INDEX ix_mutation_audit_event_tenant_target
    ON t_mutation_audit_event (tenant_club_id, target_entity_type, occurred_at DESC);
CREATE INDEX ix_mutation_audit_event_tenant_actor
    ON t_mutation_audit_event (tenant_club_id, actor_user_id, occurred_at DESC);
CREATE INDEX ix_mutation_audit_event_request_id
    ON t_mutation_audit_event (request_id)
    WHERE request_id IS NOT NULL;

