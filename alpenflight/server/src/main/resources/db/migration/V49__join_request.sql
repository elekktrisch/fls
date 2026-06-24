-- S-178 — a pilot's self-serve request to join a club. One row per submit; the
-- admin approves/denies, the pilot withdraws. Greenfield: legacy has no join-by-
-- code mechanism (registration is admin-push only), so this table only ever
-- WRITES new rows into the existing schema.
--
-- Per ADR 0022 directive 2 the schema is STRUCTURAL only. The lifecycle FSM
-- (pending → approved | denied | withdrawn; the three targets terminal) lives
-- on the JoinRequest aggregate in Java — NO CHECK pins the legal `status` set or
-- the transition rule. The length caps on `note` / `decision_reason` are likewise
-- enforced on the aggregate (FreeText.normalize); the columns are plain TEXT.
--
-- Tenant-scoped on `club_id` via Hibernate @TenantId (ADR 0008). The FK keeps the
-- legacy constraint name shape (`fk_join_request_club_id`, no `t_` prefix) per
-- ADR 0025 — the leakage sweep pins fail-closed writes to that exact name.

CREATE TABLE t_join_request (
    id                  UUID         NOT NULL PRIMARY KEY,
    keycloak_sub        UUID         NOT NULL,
    email               TEXT         NOT NULL,
    friendly_name       TEXT         NOT NULL,
    club_id             UUID         NOT NULL
                            CONSTRAINT fk_join_request_club_id REFERENCES t_club(id),
    note                TEXT             NULL,
    status              TEXT         NOT NULL,
    created_on          TIMESTAMPTZ  NOT NULL,
    decided_on          TIMESTAMPTZ      NULL,
    decided_by_user_id  UUID             NULL,
    decision_reason     TEXT             NULL
);

-- Identity-bearing partial UNIQUE: at most ONE open (pending) request per
-- (sub, club). A withdrawn/denied/approved row leaves the pair free to re-submit.
-- 'PENDING' is uppercase — @Enumerated(EnumType.STRING) on JoinRequestStatus
-- writes JoinRequestStatus.name(), which is always uppercase.
CREATE UNIQUE INDEX ux_join_request_alive
    ON t_join_request (keycloak_sub, club_id)
    WHERE status = 'PENDING';

-- The admin pending-list reads (club_id, status); the pilot's "my latest request"
-- reads (keycloak_sub, created_on desc). The partial UNIQUE above covers the
-- (sub, club) pending lookup; this index covers the per-pilot history walk.
CREATE INDEX ix_join_request_sub_created
    ON t_join_request (keycloak_sub, created_on DESC);

COMMENT ON TABLE t_join_request IS
    'Pilot self-serve club-join request (S-178). FSM in Java per ADR 0022 D2 — no CHECK on status.';

COMMENT ON COLUMN t_join_request.status IS
    'Java enum JoinRequestStatus. Terminal states: APPROVED, DENIED, WITHDRAWN.';
