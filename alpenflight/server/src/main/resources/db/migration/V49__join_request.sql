
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

CREATE UNIQUE INDEX ux_join_request_alive
    ON t_join_request (keycloak_sub, club_id)
    WHERE status = 'PENDING';

CREATE INDEX ix_join_request_sub_created
    ON t_join_request (keycloak_sub, created_on DESC);

COMMENT ON TABLE t_join_request IS
    'Pilot self-serve club-join request (S-178). FSM in Java per ADR 0022 D2 — no CHECK on status.';

COMMENT ON COLUMN t_join_request.status IS
    'Java enum JoinRequestStatus. Terminal states: APPROVED, DENIED, WITHDRAWN.';
