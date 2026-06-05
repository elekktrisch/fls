-- =============================================================================
-- J-4 T-02 — showcase seed for the `/profile` self-edit screen.
--
-- The 4-tab self-edit surface (Account / Personal / Pilot / Notifications)
-- needs showcase data so every tab renders REAL values, and a no-Person
-- principal so the "ask your club admin to link your member record" banner
-- edge is reachable. The spec (profile/self-edit.spec.ts) drives the screen
-- as the real low-privilege PILOT `pilot1@example.com` (J-3 real-roles lesson).
--
-- WHAT THIS MIGRATION SEEDS (and what it deliberately does NOT):
--
--   * pilot1's SELF-EDIT data (Person contact/licence/medical + PersonClub
--     notification prefs) is NOT seeded here. It lives in the `ShowcaseSeeder`
--     HARNESS (`@Profile("showcase")`, `seedPersonsAndLinks`), enriched onto
--     pilot1's REAL flights-PIC person (person band …7601…0601 — the one the 8
--     club-1 showcase flights are crewed against, J-3 T-03b).
--
--     WHY NOT HERE (J-4 T-14 — fixes a J-3 dashboard regression): Flyway runs
--     BEFORE the showcase harness. The earlier version of this migration created
--     a SEPARATE pilot1 Person (…7300…0002) and grabbed pilot1's
--     `t_user.person_id` with `… AND person_id IS NULL`. That won the race: the
--     harness's own `linkUserPerson(pilot1 → …7601…0601)` (also guarded on
--     `person_id IS NULL`) then no-op'd, so pilot1 pointed at an ORPHAN person
--     that is PIC on ZERO flights → `GET /flights?personId=…7300…0002` returned
--     nothing → the J-3 dashboard last-flight card went empty
--     (`start-dashboard.spec.ts:254`). The fix: enrich the harness's existing
--     flights-PIC person instead, so ONE person backs both the J-3 last-flight
--     card AND the J-4 profile tabs. See `ShowcaseSeeder.insertPilot1Person` /
--     `insertPilot1PersonClub`.
--
--   * The NO-PERSON principal IS seeded here — it is independent of the flight
--     matrix and correct at migration time. REUSES the existing `pilot-empty1`
--     realm user (sub 019e30c3-…0020, PILOT, club-1, verified email — already in
--     realm-export.json). We seed only a `t_user` row with `person_id = NULL`,
--     so JWT→tenant resolution succeeds (the Account tab still edits) while
--     Personal/Pilot/Notifications show the no-Person banner with disabled forms.
--
-- Idempotent the same way as the V8/V26/V28/V29 dev seeds: `ON CONFLICT … DO
-- NOTHING` on the fixed PK. Dev/test seed only — subsumed by S-052
-- JIT-on-first-login / KC-driven invite, same as the sibling seeds.
-- =============================================================================

-- No-Person principal: reuse the `pilot-empty1` realm user. Seed only the
-- t_user (person_id NULL) so tenant resolution works (Account edits) and the
-- Personal/Pilot/Notifications tabs hit the no-Person banner edge.
INSERT INTO t_user (
    id,
    club_id,
    username,
    friendly_name,
    notification_email,
    person_id,
    language_id,
    keycloak_sub
) VALUES (
    '019e30c3-2c00-7100-8000-000000000020',
    '019e30c3-2c00-7001-8000-000000000001',  -- seed-club-1
    'pilot-empty1',
    'Pilot Empty One',
    'pilot-empty1@example.com',
    NULL,                                    -- no Person → banner edge
    '019e2e15-2c00-77d0-8000-0000000007d0',  -- de
    '019e30c3-2c00-7200-8000-000000000020'   -- keycloak sub from realm-export (pilot-empty1)
)
ON CONFLICT (id) DO NOTHING;
