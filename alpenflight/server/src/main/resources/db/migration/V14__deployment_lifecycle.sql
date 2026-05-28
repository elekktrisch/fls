-- S-137 — Deployment entity: the tenancy parent above Club.
--
-- One legacy FLS upload bundle = one Deployment containing 1..N Clubs
-- (vision C31 + C34). Trial countdown, subscription IDs, freemium plan,
-- and lifecycle state live here; Club stays the @TenantId carrier so
-- cross-Club isolation inside one Deployment is preserved (ADR 0008).
--
-- Per ADR 0022 directive 2 schema = structural only:
--   * lifecycle_state is a string column. The legal-transition table
--     lives on the Deployment aggregate (Java map + mutator methods).
--   * plan is a string column. Derivation invariant (assertPlanConsistent)
--     lives on the aggregate; NO generated column.
--   * ux_deployment_owner_active is identity-structural — one user holds
--     at most one non-terminal Deployment. Closes the second-ingest race
--     for S-138 at the DB level; the deleting + sandbox states are exempt
--     because data is going / shared-fixed.

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

-- Partial UNIQUE: one user holds at most one non-terminal Deployment.
-- Source-of-truth for S-138's second-ingest 409 gate. {sandbox, deleting}
-- excluded — sandbox is the shared-fixed singleton (sentinel owner sub);
-- deleting Deployments are mid-cascade and the user may legitimately ingest
-- again afterward.
CREATE UNIQUE INDEX ux_deployment_owner_active
    ON t_deployment (owner_keycloak_sub)
    WHERE lifecycle_state IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'CANCELLED');

-- -----------------------------------------------------------------------------
-- Sandbox singleton. Fixed UUID so SandboxResetJob (S-082) +
-- Deployment.SANDBOX_ID Java constant + S-135 reference by id. Owner-sub
-- is the nil-UUID sentinel: the partial UNIQUE excludes sandbox so no
-- collision with a real user, and lifecycle_state=SANDBOX is the
-- immutable terminal state asserted by Deployment.transitionTo.
-- -----------------------------------------------------------------------------

INSERT INTO t_deployment(id, name, owner_keycloak_sub, lifecycle_state, plan)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'sandbox',
    '00000000-0000-0000-0000-000000000000',
    'SANDBOX',
    'FREE'
)
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------------------------------
-- Operator-owned Deployment. Hosts every pre-S-137 Club (the legacy-imported
-- data); the operator administers it via the admin endpoint. Owner-sub
-- comes from the ${alpenflight.operator.keycloak_sub} Flyway placeholder —
-- application-dev.yml + application-test.yml carry a sentinel default;
-- prod must set ALPENFLIGHT_OPERATOR_KEYCLOAK_SUB or the migration fails
-- loud. The deterministic UUID lets the backfill UPDATE below reference it
-- by literal without a subquery.
-- -----------------------------------------------------------------------------

INSERT INTO t_deployment(id, name, owner_keycloak_sub, lifecycle_state, plan)
VALUES (
    '00000000-0000-0000-0000-000000000002',
    'operator',
    '${alpenflight.operator.keycloak_sub}'::uuid,
    'ACTIVE',
    'ACTIVE'
)
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------------------------------
-- Club.deployment_id FK. Per ADR 0022 D2 the FK is structural; per Performance
-- plan an explicit btree index is required (Postgres does not auto-create
-- FK indexes; forEachClub does SELECT … WHERE deployment_id = ? every tick).
-- ON DELETE RESTRICT — the cascade-on-delete worker (S-142) owns the
-- transactional teardown of child Clubs; a naive ON DELETE CASCADE here would
-- skip the audit-emission half of that worker.
-- -----------------------------------------------------------------------------

-- The operator-Deployment id is the backfill default — every pre-existing
-- Club belongs to the operator until S-138's trial-ingest path moves new
-- Clubs under their owner's TRIAL Deployment. The DEFAULT also catches
-- direct-JDBC inserts in test fixtures + bootstrap scripts that pre-date
-- this column; production code goes through ClubsService.createClub
-- (S-048) which passes deploymentId explicitly via Club.create.
ALTER TABLE t_club
    ADD COLUMN deployment_id UUID NOT NULL
        DEFAULT '00000000-0000-0000-0000-000000000002';

ALTER TABLE t_club
    ADD CONSTRAINT fk_club_deployment_id
    FOREIGN KEY (deployment_id) REFERENCES t_deployment (id) ON DELETE RESTRICT;

CREATE INDEX ix_club_deployment_id ON t_club (deployment_id);
