
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

CREATE UNIQUE INDEX ux_migration_run_upload_active
    ON t_migration_run (upload_id)
    WHERE state IN ('DECRYPTING','PROVISIONING','INGESTING','COMPLETING');

CREATE INDEX ix_migration_run_upload_started_at
    ON t_migration_run (upload_id, started_at DESC);

COMMENT ON TABLE t_migration_run IS
    'Per-bundle ingest run (S-141). State machine in Java per ADR 0022 D2 — no CHECK.';

COMMENT ON COLUMN t_migration_run.state IS
    'Java enum MigrationRunState. Terminal states: completed, failed.';

COMMENT ON COLUMN t_migration_run.warnings IS
    'jsonb array of {code, entityType, clubId, legacyGuid?, detail}. Read by S-187a parity-run report.';
