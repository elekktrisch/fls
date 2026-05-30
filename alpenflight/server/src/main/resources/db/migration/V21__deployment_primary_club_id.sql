-- S-141 — resolves the S-138 open question on the divergence between
-- DeploymentProvisioningService.provision (writes the manifest hint /
-- lowest-UUID fallback) and reconcileKeycloak (re-computed lowest UUID).
-- Both paths now read this column; the lowest-UUID fallback inside
-- reconcileKeycloak is dropped — single source of truth lives on the row.
--
-- Nullable: pre-S-141 Deployments (sandbox singleton + operator-owned +
-- IT artifacts) don't carry a primary Club because they pre-date the
-- multi-Club bundle ingest path. Sandbox + operator are operator-administered
-- and never touched by the ingest pipeline; the column stays NULL for them
-- forever. Reconcile post-S-141 always populates the column atomically with
-- the Club rows in the same provisioning txn.

ALTER TABLE t_deployment
    ADD COLUMN primary_club_id UUID;

ALTER TABLE t_deployment
    ADD CONSTRAINT fk_deployment_primary_club_id
    FOREIGN KEY (primary_club_id) REFERENCES t_club(id) ON DELETE RESTRICT;

COMMENT ON COLUMN t_deployment.primary_club_id IS
    'S-141 / S-138 — the manifest-declared primary Club for this Deployment, or the lowest-UUID Club when the manifest did not specify. Read by reconcileKeycloak when setting the user attribute.';
