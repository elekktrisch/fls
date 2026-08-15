
CREATE TABLE t_deployment (
    id                      UUID         NOT NULL PRIMARY KEY,
    name                    VARCHAR(200) NOT NULL,
    owner_keycloak_sub      UUID         NOT NULL,
    lifecycle_state         VARCHAR(32)  NOT NULL,
    plan                    VARCHAR(16)  NOT NULL,
    trial_started_at        TIMESTAMPTZ,
    billing_customer_id     TEXT,
    billing_subscription_id TEXT,
    version                 BIGINT       NOT NULL DEFAULT 0,
    created_on              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    modified_on             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_deployment_lifecycle ON t_deployment (lifecycle_state);

CREATE UNIQUE INDEX ux_deployment_owner_active
    ON t_deployment (owner_keycloak_sub)
    WHERE lifecycle_state IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'CANCELLED');


INSERT INTO t_deployment(id, name, owner_keycloak_sub, lifecycle_state, plan)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'sandbox',
    '00000000-0000-0000-0000-000000000000',
    'SANDBOX',
    'FREE'
)
ON CONFLICT (id) DO NOTHING;


INSERT INTO t_deployment(id, name, owner_keycloak_sub, lifecycle_state, plan)
VALUES (
    '00000000-0000-0000-0000-000000000002',
    'operator',
    '${alpenflight.operator.keycloak_sub}'::uuid,
    'ACTIVE',
    'ACTIVE'
)
ON CONFLICT (id) DO NOTHING;


ALTER TABLE t_club
    ADD COLUMN deployment_id UUID NOT NULL
        DEFAULT '00000000-0000-0000-0000-000000000002';

ALTER TABLE t_club
    ADD CONSTRAINT fk_club_deployment_id
    FOREIGN KEY (deployment_id) REFERENCES t_deployment (id) ON DELETE RESTRICT;

CREATE INDEX ix_club_deployment_id ON t_club (deployment_id);
