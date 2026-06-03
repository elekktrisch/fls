-- =============================================================================
-- J-2 T-24 — fourth dev/test principal `clubadmin4`, the PRE-SEEDED STABLE
-- principal for the clean-seed MOTOR `/airmovements` e2e (flight-migration-
-- parity.spec.ts:282).
--
-- WHY a seeded principal instead of the dynamic `provisionExtraClubAdmin`
-- (T-22) it replaces:
--
--   The motor test is the SECOND interactive club-A login in one Playwright
--   invocation (the glider test logs in first). The earlier dynamic motor admin
--   relied on JIT-on-first-login to materialise its `t_user`, and that JIT path
--   loses a race: a second principal landing on a contended `preferred_username`
--   trips `ux_user_username_lower_alive`, the race-recovery re-reads by SUB (not
--   username) and returns empty, and the loser is left permanently tenant-less —
--   every @TenantId read then misses its `club_id` and `/airmovements` never
--   loads (J-2 T-22). Worse, the harness was authenticating the motor page as
--   the seeded `sysadmin` (sub f1558768-…, NO clubId attribute, NO `t_user`) via
--   SSO/session bleed from the `captureSysadminBearer` club-B-create login — so
--   the page got `user-lookup miss column=club_id matches=0` on every request,
--   stable across three CI runs (T-23, T-24 diagnosis).
--
--   A PRE-SEEDED principal with its OWN `t_user` row sidesteps the race
--   entirely: tenant resolution is PreTenantUserLookup
--   (SELECT id FROM t_user WHERE keycloak_sub = ?), which resolves deterministically
--   the moment the principal authenticates — no JIT materialisation at runtime,
--   no username contention, no SSO-bleed dependence on which login ran first.
--   This mirrors the GREEN migration principal `clubadmin3` (V28): a verified-
--   email realm user + a seeded `t_user`.
--
--   Bound to seed-club-1 (`019e30c3-2c00-7001-8000-000000000001`) — the club
--   where `seedFlightMasterdata` creates the motor aircraft / flight-type /
--   pilot / location through the real APIs as club-A's admin, so the motor admin
--   sees exactly that masterdata in the wizard dropdowns.
--
-- Same realm-export shape (verified-email CLUB_ADMINISTRATOR + OFFICE_USER,
-- clubId=club-1, de) and the same `ON CONFLICT (id) DO NOTHING` idempotency as
-- the V8/V26/V28 dev-user seeds. A dev/test seed until S-052 JIT-on-first-login
-- subsumes it.
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
        '019e30c3-2c00-7100-8000-000000000012',
        '019e30c3-2c00-7001-8000-000000000001',  -- seed-club-1
        'clubadmin4',
        'Club Admin Four',
        'clubadmin4@example.com',
        '019e2e15-2c00-77d0-8000-0000000007d0',  -- de
        'c1ab4d40-0000-4000-8000-000000000004'   -- keycloak sub from realm-export (clubadmin4)
    )
ON CONFLICT (id) DO NOTHING;
