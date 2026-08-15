
CREATE TABLE t_job_run (
    id           UUID         NOT NULL PRIMARY KEY,
    job_name     VARCHAR(128) NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    started_at   TIMESTAMPTZ  NOT NULL,
    finished_at  TIMESTAMPTZ      NULL,
    summary      TEXT             NULL,
    error        TEXT             NULL
);

CREATE INDEX ix_job_run_job_name_started_at
    ON t_job_run (job_name, started_at DESC);

COMMENT ON TABLE t_job_run IS
    'Last-run store for @MeasuredJob business jobs (S-038). Platform / cross-tenant — no @TenantId. Status FSM in Java per ADR 0022 D2 — no CHECK.';

COMMENT ON COLUMN t_job_run.status IS
    'Java enum JobRun.Status: RUNNING / COMPLETED / FAILED. NEVER_RUN is the absence of any row.';
