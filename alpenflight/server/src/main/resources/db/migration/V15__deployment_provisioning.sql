-- S-138 — Trial-Deployment provisioning on first successful migration ingest.
--
-- Adds two columns to t_deployment:
--   * idempotency_key — the parent identifier the caller binds to a durable
--     cross-attempt artifact (S-141 binds it to migration_run.id). A retried
--     ingest of the same upload short-circuits to the existing Deployment
--     instead of re-running Phase A. UNIQUE so a concurrent race surfaces
--     as a constraint violation rather than two Deployments.
--   * kc_state — 'pending' until the Keycloak Phase B reconcile (group +
--     per-Club realm roles + clubId user attribute) completes; flipped to
--     'ready' on success. The S-141 hourly retry job re-invokes Phase B
--     for every Deployment still 'pending'.
--
-- Per ADR 0022 directive 2 the schema enforces structural identity only:
-- the UNIQUE on idempotency_key is identity-bearing (one Deployment per
-- migration_run); the kc_state values are not constrained at the DB level —
-- the legal-value set lives in the KeycloakReconcileState Java enum.
--
-- Adds a UNIQUE for t_member_state(club_id, name) where deleted_on IS NULL.
-- The reference-data seeder uses ON CONFLICT (club_id, name) DO NOTHING so
-- a manifest carrying a customized MemberState wins over the default. The
-- t_flight_type UNIQUE already exists (V11); t_flight_cost_balance_type is
-- system-global and seeded by V3 baseline — no per-Club bootstrap.

ALTER TABLE t_deployment
    ADD COLUMN idempotency_key UUID,
    ADD COLUMN kc_state        VARCHAR(16) NOT NULL DEFAULT 'pending';

-- Backfill: existing Deployments (sandbox + operator + any IT rows from
-- a prior boot of the test container) are already past provisioning. Mark
-- them 'ready' so the S-141 retry job ignores them, and stamp a generated
-- UUID idempotency_key per row so the UNIQUE doesn't reject the column
-- promotion. Production starts empty; this path covers the test JVM's
-- container-reuse case.
UPDATE t_deployment SET kc_state = 'ready', idempotency_key = gen_random_uuid()
    WHERE idempotency_key IS NULL;

CREATE UNIQUE INDEX ux_deployment_idempotency_key
    ON t_deployment (idempotency_key);

-- Identity-bearing partial UNIQUE on (club_id, name) for the
-- reference-data seeder's ON CONFLICT target. Excludes soft-deleted rows
-- so a tenant may soft-delete and recreate the same MemberState name
-- (mirrors the V11 FlightType pattern).
CREATE UNIQUE INDEX ux_member_state_club_name
    ON t_member_state (club_id, name)
    WHERE deleted_on IS NULL;
