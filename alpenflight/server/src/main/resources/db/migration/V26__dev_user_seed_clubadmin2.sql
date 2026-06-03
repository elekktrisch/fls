-- =============================================================================
-- J-1 T-17 — second migration principal `clubadmin2` so co-located parity specs
-- ingest as DISJOINT callers.
--
-- The migration bundle endpoint (MigrationBundleController) provisions a
-- NON-TERMINAL Deployment OWNED BY THE PRINCIPAL'S Keycloak sub
-- (DeploymentProvisioningService#provision → findActiveByOwner(ownerKeycloakSub),
-- the `ux_deployment_owner_active` gate). When two parity specs ingest in the
-- SAME `playwright test` invocation (alpenflight-proof-fanout.yml: J-0c fan-out
-- + J-1 aircraft) as the SAME principal `clubadmin1`, the second ingest 409s
-- DEPLOYMENT_EXISTS on the first spec's still-active Deployment.
--
-- Isolation: give the J-1 aircraft parity spec its OWN migration principal
-- (`clubadmin2`, distinct Keycloak user id / JWT sub in realm-export.json) so
-- `findActiveByOwner` resolves a different owner and never collides. J-0c stays
-- on `clubadmin1`. This seeds the matching `t_user` row so PreTenantUserLookup
-- (SELECT id FROM t_user WHERE keycloak_sub = ?) resolves the principal — the
-- bundle endpoint requires a verified-email JWT that maps to a `t_user`.
--
-- Same tenant (seed-club-1) and language (de) as the V8 clubadmin1 seed; the
-- tenant is irrelevant to the migration ingest (it provisions NEW clubs from the
-- bundle manifest) — the row exists only so principal resolution succeeds.
-- Follows the V8 going-in posture: a dev/test seed until S-052 JIT-on-first-login
-- replaces it. Same `ON CONFLICT (id) DO NOTHING` idempotency.
-- =============================================================================

INSERT INTO t_user (
    id,
    club_id,
    username,
    friendly_name,
    notification_email,
    language_id,
    keycloak_sub
) VALUES
    (
        '019e30c3-2c00-7100-8000-000000000010',
        '019e30c3-2c00-7001-8000-000000000001',  -- seed-club-1
        'clubadmin2',
        'Club Admin Two',
        'clubadmin2@example.com',
        '019e2e15-2c00-77d0-8000-0000000007d0',  -- de
        '9d08ed9c-699a-4c26-9036-9f0bd378002a'   -- keycloak sub from realm-export (clubadmin2)
    )
ON CONFLICT (id) DO NOTHING;
