-- =============================================================================
-- J-4 T-02 — showcase seed for the `/profile` self-edit screen.
--
-- The 4-tab self-edit surface (Account / Personal / Pilot / Notifications)
-- needs showcase data so every tab renders REAL values, and a no-Person
-- principal so the "ask your club admin to link your member record" banner
-- edge is reachable. The spec (profile/self-edit.spec.ts) drives the screen
-- as the real low-privilege PILOT `pilot1@example.com` (J-3 real-roles lesson).
--
-- Two things are seeded here:
--
--   1. A SELF-EDITABLE PILOT principal — REUSES the existing `pilot1` realm
--      user (sub 376317c0-…, V8 t_user 019e30c3-…0002, seed-club-1). No new
--      Keycloak realm user. We give it a fully-populated `t_person`, link it
--      via `t_user.person_id`, attach a `t_person_club` membership in
--      pilot1's club with known notification-pref values, and fill the
--      mutable `t_user` self-fields (friendly_name / notification_email /
--      phone_number / language_id). So all four tabs show real data and the
--      migrated/showcase round-trip AC passes.
--
--   2. A NO-PERSON principal — REUSES the existing `pilot-empty1` realm user
--      (sub 019e30c3-…0020, PILOT, club-1, verified email — already in
--      realm-export.json, no t_user row until now). We seed only a `t_user`
--      row with `person_id = NULL`, so JWT→tenant resolution succeeds (the
--      Account tab still edits) while Personal/Pilot/Notifications show the
--      no-Person banner with disabled forms.
--
-- Idempotent the same way as the V8/V26/V28/V29 dev seeds: `ON CONFLICT … DO
-- NOTHING` on the fixed PKs; the `t_user.person_id` link is applied with a
-- guarded UPDATE so re-running is a no-op. Dev/test seed only — subsumed by
-- S-052 JIT-on-first-login / KC-driven invite, same as the sibling seeds.
--
-- Reference data reused from V2: country CH (019e2e15-…04be), language de
-- (019e2e15-…07d0). member_state_id is left NULL — no t_member_state rows are
-- seeded for seed-club-1 (per-club masterdata, not in the baseline seed), and
-- the FK fk_person_club_member_state_id would reject a dangling reference;
-- member_number carries the membership-identity value instead.
-- =============================================================================

-- 1a — fully-populated Person for pilot1 (all four tabs render real values).
INSERT INTO t_person (
    id,
    lastname,
    firstname,
    midname,
    company_name,
    address_line1,
    zip,
    city,
    region,
    country_id,
    private_phone,
    mobile_phone,
    business_phone,
    email_private,
    email_business,
    prefer_mail_to_business_mail,
    birthday,
    -- Pilot tab: glider-pilot licence flag set + matching medical expiry.
    has_glider_pilot_licence,
    licence_number,
    medical_class2_expire_date,
    enable_address
) VALUES (
    '019e30c3-2c00-7300-8000-000000000002',  -- pilot1's Person (fixed PK)
    'One',
    'Pilot',
    NULL,
    NULL,
    'Flugplatzstrasse 1',
    '3000',
    'Bern',
    'BE',
    '019e2e15-2c00-74be-8000-0000000004be',  -- CH
    '+41 31 000 00 01',
    '+41 79 000 00 01',
    '+41 31 000 00 02',
    'pilot1.private@example.com',
    'pilot1.business@example.com',
    false,
    '1985-06-15',
    true,                                    -- has_glider_pilot_licence
    'CH-GLD-0001',
    '2027-09-30',                            -- medical_class2_expire_date
    true
)
ON CONFLICT (id) DO NOTHING;

-- 1b — pilot1's club membership in seed-club-1 with known notification-pref
--      values (flightReports=true, reservations=false, clubNews=true) and a
--      member number / glider-pilot role flag.
INSERT INTO t_person_club (
    id,
    person_id,
    club_id,
    member_number,
    is_glider_pilot,
    receive_flight_reports,
    receive_aircraft_reservation_notifications,
    receive_planning_day_role_reminder,
    is_active
) VALUES (
    '019e30c3-2c00-7400-8000-000000000002',  -- pilot1's PersonClub (fixed PK)
    '019e30c3-2c00-7300-8000-000000000002',  -- pilot1's Person
    '019e30c3-2c00-7001-8000-000000000001',  -- seed-club-1
    'M-0001',
    true,                                    -- is_glider_pilot
    true,                                    -- receive_flight_reports
    false,                                   -- receive_aircraft_reservation_notifications
    true,                                    -- receive_planning_day_role_reminder
    true                                     -- is_active
)
ON CONFLICT (id) DO NOTHING;

-- 1c — link pilot1's User row (V8) to its Person, and fill the mutable
--      Account-tab self-fields. Guarded so a re-run is a no-op.
UPDATE t_user
SET person_id          = '019e30c3-2c00-7300-8000-000000000002',
    friendly_name      = 'Pilot One',
    notification_email = 'pilot1@example.com',
    phone_number       = '+41 79 000 00 01',
    language_id        = '019e2e15-2c00-77d0-8000-0000000007d0'  -- de
WHERE id = '019e30c3-2c00-7100-8000-000000000002'                -- pilot1 (V8)
  AND person_id IS NULL;

-- 2 — no-Person principal: reuse the `pilot-empty1` realm user. Seed only the
--     t_user (person_id NULL) so tenant resolution works (Account edits) and
--     the Personal/Pilot/Notifications tabs hit the no-Person banner edge.
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
