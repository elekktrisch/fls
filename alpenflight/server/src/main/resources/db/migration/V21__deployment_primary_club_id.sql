
ALTER TABLE t_deployment
    ADD COLUMN primary_club_id UUID;

ALTER TABLE t_deployment
    ADD CONSTRAINT fk_deployment_primary_club_id
    FOREIGN KEY (primary_club_id) REFERENCES t_club(id) ON DELETE RESTRICT;

COMMENT ON COLUMN t_deployment.primary_club_id IS
    'S-141 / S-138 — the manifest-declared primary Club for this Deployment, or the lowest-UUID Club when the manifest did not specify. Read by reconcileKeycloak when setting the user attribute.';
