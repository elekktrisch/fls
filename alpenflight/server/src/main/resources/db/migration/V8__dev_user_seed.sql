-- =============================================================================
-- Dev/test seed for the `t_user` table so the JWT → tenant resolution chain
-- works end-to-end against the seeded Keycloak realm.
--
-- Today `ClubTenantIdentifierResolver` either parses a UUID `clubId` claim
-- (realm-export currently carries the string "club-1", which fails parse) or
-- falls back to `UserPrincipalLookup.resolveTenantFor(jwt)` which queries
-- `t_user` by `keycloak_sub`. Without a row, that lookup returns empty →
-- NO_TENANT (nil UUID) → every tenant-scoped write fails at
-- `fk_<table>_club_id`.
--
-- This migration seeds the two club-scoped Keycloak users into seed-club-1
-- so clubadmin1 / pilot1 can create / read tenant-scoped masterdata in dev.
-- Sysadmin is intentionally not seeded: per the S-159 design pivot, sysadmin
-- operates only on cross-cutting resources (Clubs catalog, sysadmin user
-- mgmt, cutover import) and does not own a tenant. S-052 replaces this seed
-- with KC-driven invite (CLUB_ADMIN flow) + JIT-on-first-login (bulk import
-- / federated IdP) — keep this seed only until JIT has shaken out one full
-- dev bring-up cycle without it.
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
        '019e30c3-2c00-7100-8000-000000000001',
        '019e30c3-2c00-7001-8000-000000000001',  -- seed-club-1
        'clubadmin1',
        'Club Admin One',
        'clubadmin1@example.com',
        '019e2e15-2c00-77d0-8000-0000000007d0',  -- de
        '9d08ed9c-699a-4c26-9036-9f0bd378009d'   -- keycloak sub from realm-export
    ),
    (
        '019e30c3-2c00-7100-8000-000000000002',
        '019e30c3-2c00-7001-8000-000000000001',  -- seed-club-1
        'pilot1',
        'Pilot One',
        'pilot1@example.com',
        '019e2e15-2c00-77d0-8000-0000000007d0',  -- de
        '376317c0-fc0a-439d-a5f7-9af17e5f4178'   -- keycloak sub from realm-export
    )
ON CONFLICT (id) DO NOTHING;
