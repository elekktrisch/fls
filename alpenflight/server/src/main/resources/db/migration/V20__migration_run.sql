-- S-141 Per-bundle ingest run. Parent row for the streaming decrypt + ingest
-- pipeline; one row per POST /api/v1/migrations/{uploadId}/bundle attempt.
-- The S-141 service drives the lifecycle FSM; S-187a reads `warnings` to
-- write the parity-run report.
--
-- Lifecycle (in Java per ADR 0022 directive 2 — no CHECK on state):
--   decrypting → provisioning → ingesting → completing → completed
--                                                     → failed (terminal)
--
-- Plaintext-leak posture: `error_detail` and `warnings` are @AuditRedact
-- on the aggregate field — a failing-row's PII may end up in error_detail
-- and the warnings jsonb shape is `[{code, entityType, clubId, legacyGuid?,
-- detail}]`. The audit-snapshot record (MigrationIngestAuditSnapshot) does
-- not include either column; the redaction annotation defends against a
-- future code path that hands the entity straight to the redactor.

CREATE TABLE t_migration_run (
    id                  UUID         NOT NULL PRIMARY KEY,
    upload_id           UUID         NOT NULL REFERENCES t_migration_upload(id),
    state               VARCHAR(32)  NOT NULL,
    current_entity      VARCHAR(64)      NULL,
    current_club_id     UUID             NULL,
    started_at          TIMESTAMPTZ  NOT NULL,
    completed_at        TIMESTAMPTZ      NULL,
    deployment_id       UUID             NULL REFERENCES t_deployment(id),
    error_code          VARCHAR(64)      NULL,
    error_detail        TEXT             NULL,
    warnings            JSONB        NOT NULL DEFAULT '[]'::jsonb
);

-- Identity-bearing partial UNIQUE: at most one non-terminal run per upload.
-- Catches double-POST race; the FOR UPDATE NOWAIT on t_migration_upload(id)
-- inside the ingest txn is the second line of defense.
-- State literals are UPPERCASE — @Enumerated(EnumType.STRING) on
-- MigrationRunState writes MigrationRunState.name() (always uppercase).
CREATE UNIQUE INDEX ux_migration_run_upload_active
    ON t_migration_run (upload_id)
    WHERE state IN ('DECRYPTING','PROVISIONING','INGESTING','COMPLETING');

-- Status-poll lookup walks (upload_id, started_at desc) to return the most
-- recent run for a given upload. PK + this index cover both the polling
-- read and the "is there a run in flight" check.
CREATE INDEX ix_migration_run_upload_started_at
    ON t_migration_run (upload_id, started_at DESC);

COMMENT ON TABLE t_migration_run IS
    'Per-bundle ingest run (S-141). State machine in Java per ADR 0022 D2 — no CHECK.';

COMMENT ON COLUMN t_migration_run.state IS
    'Java enum MigrationRunState. Terminal states: completed, failed.';

COMMENT ON COLUMN t_migration_run.warnings IS
    'jsonb array of {code, entityType, clubId, legacyGuid?, detail}. Read by S-187a parity-run report.';
