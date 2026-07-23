-- S-038 last-run store for @MeasuredJob business jobs. One append-only row per
-- run: the aspect inserts at RUNNING then transitions the same row in place to
-- COMPLETED / FAILED. The /system/jobs console reads the most recent row per
-- job_name; absence of any row ↔ NEVER_RUN.
--
-- Platform / cross-tenant table: jobs run unscoped across all clubs and the
-- console is SYSTEM_ADMINISTRATOR-gated (no tenant), so there is deliberately
-- NO club_id / operating_club_id column and NO @TenantId on the aggregate —
-- same posture as t_deployment / t_migration_run (greenfield platform tables,
-- no legacy origin, no tenant-rules.yaml override).
--
-- Lifecycle (in Java per ADR 0022 directive 2 — no CHECK on status):
--   running → completed
--           → failed

CREATE TABLE t_job_run (
    id           UUID         NOT NULL PRIMARY KEY,
    job_name     VARCHAR(128) NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    started_at   TIMESTAMPTZ  NOT NULL,
    finished_at  TIMESTAMPTZ      NULL,
    summary      TEXT             NULL,
    error        TEXT             NULL
);

-- Console reads the latest run per job via (job_name, started_at desc); this
-- index covers both that lookup and the "any run for this job" existence check.
CREATE INDEX ix_job_run_job_name_started_at
    ON t_job_run (job_name, started_at DESC);

COMMENT ON TABLE t_job_run IS
    'Last-run store for @MeasuredJob business jobs (S-038). Platform / cross-tenant — no @TenantId. Status FSM in Java per ADR 0022 D2 — no CHECK.';

COMMENT ON COLUMN t_job_run.status IS
    'Java enum JobRun.Status: RUNNING / COMPLETED / FAILED. NEVER_RUN is the absence of any row.';
