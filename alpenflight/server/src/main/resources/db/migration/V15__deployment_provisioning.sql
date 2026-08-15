
ALTER TABLE t_deployment
    ADD COLUMN idempotency_key UUID,
    ADD COLUMN kc_state        VARCHAR(16) NOT NULL DEFAULT 'PENDING';

UPDATE t_deployment SET kc_state = 'READY'
    WHERE kc_state = 'PENDING';

CREATE UNIQUE INDEX ux_deployment_idempotency_key
    ON t_deployment (idempotency_key);

CREATE UNIQUE INDEX ux_member_state_club_name
    ON t_member_state (club_id, name)
    WHERE deleted_on IS NULL;
