-- =============================================================================
-- J-2 T-18 — third migration principal `clubadmin3` so the THREE co-located
-- real-idp parity specs ingest their migration bundles as DISJOINT callers.
--
-- The migration bundle endpoint (MigrationBundleController) provisions a
-- NON-TERMINAL Deployment OWNED BY THE PRINCIPAL'S Keycloak sub
-- (DeploymentProvisioningService#provision → findActiveByOwner(ownerKeycloakSub),
-- the `ux_deployment_owner_active` gate). The J-0c Locations, J-1 Aircraft, and
-- J-2 Flights real-idp specs now run in ONE `playwright test` invocation
-- (ci.yml: the alpenflight-proof gate runs all three specs together so the one
-- proof video covers the whole journey set). J-0c ingests as `clubadmin1`
-- (V8) and J-1 ingests as `clubadmin2` (V26). J-2 had been REUSING `clubadmin2`,
-- so the second of the two ingests sharing that principal 409'd
-- DEPLOYMENT_EXISTS on the first spec's still-active Deployment (and tripped
-- `ux_user_username_lower_alive` on the provisioned-admin path) — the exact
-- J-1 T-17 collision class, re-surfaced now that all three specs co-locate.
--
-- Isolation: give the J-2 flight parity spec its OWN migration principal
-- (`clubadmin3`, distinct Keycloak user id / JWT sub in realm-export.json) so
-- `findActiveByOwner` resolves a different owner per spec and never collides.
-- J-0c stays `clubadmin1`, J-1 stays `clubadmin2`. This seeds the matching
-- `t_user` row so PreTenantUserLookup (SELECT id FROM t_user WHERE keycloak_sub
-- = ?) resolves the principal — the bundle endpoint requires a verified-email
-- JWT that maps to a `t_user`.
--
-- Same tenant (seed-club-1) and language (de) as the V8/V26 seeds; the tenant
-- is irrelevant to the migration ingest (it provisions NEW clubs from the
-- bundle manifest) — the row exists only so principal resolution succeeds.
-- Follows the V8/V26 going-in posture: a dev/test seed until S-052
-- JIT-on-first-login replaces it. Same `ON CONFLICT (id) DO NOTHING` idempotency.
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
        '019e30c3-2c00-7100-8000-000000000011',
        '019e30c3-2c00-7001-8000-000000000001',  -- seed-club-1
        'clubadmin3',
        'Club Admin Three',
        'clubadmin3@example.com',
        '019e2e15-2c00-77d0-8000-0000000007d0',  -- de
        '9d08ed9c-699a-4c26-9036-9f0bd378003b'   -- keycloak sub from realm-export (clubadmin3)
    )
ON CONFLICT (id) DO NOTHING;
