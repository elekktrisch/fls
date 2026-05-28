-- Trial-Deployment provisioning columns on t_deployment.
--
-- idempotency_key — durable cross-attempt identifier (typically the upstream
--   migration-run id). A retried ingest of the same attempt short-circuits to
--   the existing Deployment instead of re-running the provisioning service.
--   UNIQUE so a concurrent race surfaces as a constraint violation rather
--   than two Deployments. Nullable: the operator + sandbox singletons never
--   go through the provisioning path so they stay NULL. Multiple NULLs are
--   permitted under a UNIQUE index in Postgres, so the column promotion is
--   safe without backfilling synthetic values.
--
-- kc_state — PENDING until the directory-side reconcile (group +
--   per-Club realm roles + clubId user attribute) completes; READY after.
--   The hourly reconcile job filters on PENDING. Stored uppercase to
--   match @Enumerated(STRING) on the KeycloakReconcileState aggregate
--   field; legal-value set lives in Java per ADR 0022 directive 2 — no
--   schema-level CHECK.
--
-- Also adds an identity-bearing partial UNIQUE on (club_id, name) for
-- t_member_state so the per-Club reference-data seeder can use
-- ON CONFLICT (club_id, name) DO NOTHING against the V11 / V15 partial
-- indexes (bundle wins on conflict).

ALTER TABLE t_deployment
    ADD COLUMN idempotency_key UUID,
    ADD COLUMN kc_state        VARCHAR(16) NOT NULL DEFAULT 'PENDING';

-- Pre-existing rows (sandbox + operator + IT artifacts from prior
-- container reuse) are already past provisioning; mark them READY so
-- the reconcile job ignores them.
UPDATE t_deployment SET kc_state = 'READY'
    WHERE kc_state = 'PENDING';

CREATE UNIQUE INDEX ux_deployment_idempotency_key
    ON t_deployment (idempotency_key);

-- Identity-bearing partial UNIQUE on (club_id, name) — the reference-
-- data seeder's ON CONFLICT target. Excludes soft-deleted rows so a
-- tenant may soft-delete and recreate the same MemberState name
-- (mirrors the V11 FlightType pattern).
CREATE UNIQUE INDEX ux_member_state_club_name
    ON t_member_state (club_id, name)
    WHERE deleted_on IS NULL;
