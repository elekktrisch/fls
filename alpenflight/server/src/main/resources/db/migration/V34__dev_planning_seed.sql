-- =============================================================================
-- J-6 T-06 — clean-seed planning dev data for seed-club-1.
--
-- WHY: the planning surface (`/planning` list + edit + the setup wizard) needs
-- data WITHOUT going through migration-from-legacy, so the clean-seed real-idp
-- spec (`tests/real-idp/planning-migration-parity.spec.ts`, clean-seed half) and
-- the mock inner-loop specs (`tests/planning/`) render a non-empty future-days
-- list and have stable rows to assert against. The 3 well-known assignment types
-- + sample days below are what a greenfield club would carry; a MIGRATED club
-- brings its own types + days, so this is NOT seeded by migration-from-legacy —
-- only by this clean-seed dev/test seed (the same dev clubs J-0…J-5 seed).
--
-- DEV/TEST-SEED — same accepted posture as the sibling V8/V26/V28/V29/V30/V31
-- dev seeds (read V31's header): every row is bound to `seed-club-1`
-- (`019e30c3-2c00-7001-8000-000000000001`, the canonical dev/test club from
-- V5__clubs_walking_skeleton.sql). Flyway runs the single `classpath:db/migration`
-- location across EVERY profile (no profile split), so — like the V5 club itself
-- and every sibling dev seed — these rows land wherever the migrations run,
-- production INCLUDED. That is INERT in prod: the planning reads are all
-- `@TenantId`-filtered to the operating club, and real prod tenants are migrated
-- in via `POST /api/v1/clubs` as distinct runtime UUIDs that never see
-- seed-club-1's rows. The dev/test guarantee is the @TenantId isolation of the
-- seed-club-1 binding (NOT a Flyway location fence, NOT a "seed-club-1 absent in
-- prod" claim). Richer per-screen showcase data goes via the `@Profile("showcase")`
-- ShowcaseSeeder harness (J-3/J-4 pattern); masterdata-SHAPE rows like these go
-- via Flyway, idempotent on the fixed PK (`ON CONFLICT (id) DO NOTHING`).
--
-- SEED-BAND ids: every PK is in the `019e30c3-2c00-7001-8000-...` seed-band, the
-- same band as the mock-spec fixtures (`tests/planning/planning-crud.spec.ts`):
--   locations  ...c001 (Bern-Belp / LSZB) · ...c002 (Thun / LSZW)
--   persons    ...00000000b1 (instructor) · ...b2 (tow pilot) · ...b3 (flight op)
--   types      ...00000000d1 (Segelflugleiter) · ...d2 (Schlepppilot) · ...d3 (Fluglehrer)
--   days       ...000000e01 (weekday, full crew) · ...e02 (weekend, no crew)
--   crew rows  ...000000f01..f03 (the weekday day's 3 assignments)
-- A consumer (a controller-IT pre-clean) MUST spare this band — see the J-5 T-34
-- lesson (`AircraftReservationsControllerIT` wiped V31). `PlanningDaysControllerIT`
-- is scoped `AND id::text NOT LIKE '019e30c3-%'` (this task) so it never wipes
-- these rows nor brittles its exact-count assertions on the shared container.
--
-- ASSIGNMENT-TYPE NAMES are the canonical German display names legacy seeds at
-- club creation (`ClubService.cs:206-228`): `Segelflugleiter` → FLIGHT_OPERATOR,
-- `Schlepppilot` → TOWING_PILOT, `Fluglehrer` → INSTRUCTOR. The domain
-- `PlanningRole` resolver matches case-INSENSITIVELY (lower-cased German), so the
-- capitalized display names here resolve to the three roles the 3 person pickers
-- bind to (`PlanningRole.java`). `required_nr_of_assignments` is DEAD this journey
-- (always 1, never read — J-6 oracle); seeded as 1 only so JPA round-trips it.
-- =============================================================================

-- ── seed-club-1 opts in to planning-day notifications ───────────────────────
-- Set the club's planning-day notification address (V35 column) so the
-- imminent (day+1) pass of PlanningDayNotificationJob has a recipient — the
-- T-16 notification real-idp case fires the guarded run-now affordance and
-- asserts this club address receives the planningday-ok/cancel mail. Left
-- `use_planning_day_without_reservations` at its V35 default (false) so the
-- ok-vs-cancel rule is exercisable (ok when the day has a reservation, cancel
-- when it does not). INERT in prod (same @TenantId isolation as the rest of
-- this seed). Idempotent: only sets it when still unset on seed-club-1.
UPDATE t_club
   SET send_planning_day_info_mail_to = 'flugbetrieb@seed-club-1.example'
 WHERE id = '019e30c3-2c00-7001-8000-000000000001'
   AND send_planning_day_info_mail_to IS NULL;

-- ── 2 locations for seed-club-1 (Bern-Belp + Thun) ──────────────────────────
-- country = CH (V2 seed), type = GLIDER_AIRFIELD (V3 seed). club_id = seed-club-1.
INSERT INTO t_location (id, club_id, location_name, country_id, location_type_id,
        icao_code, is_inbound_route_required, is_outbound_route_required, is_fast_entry_record)
VALUES
    ('019e30c3-2c00-7001-8000-00000000c001',
     '019e30c3-2c00-7001-8000-000000000001',
     'Bern-Belp',
     '019e2e15-2c00-74be-8000-0000000004be',   -- CH
     '019e2e15-2c00-72cb-8000-0000000032cb',   -- GLIDER_AIRFIELD
     'LSZB', false, false, false),
    ('019e30c3-2c00-7001-8000-00000000c002',
     '019e30c3-2c00-7001-8000-000000000001',
     'Thun',
     '019e2e15-2c00-74be-8000-0000000004be',   -- CH
     '019e2e15-2c00-72cb-8000-0000000032cb',   -- GLIDER_AIRFIELD
     -- LSPL (not the legacy real Thun ICAO LSZW): LSZW collides with the
     -- ShowcaseSeederIT cross-club icao pre-clean set, which would FK-trip on this
     -- seed's weekend planning day. The icao is cosmetic here (planning reads by
     -- location_id), so a non-colliding code keeps the seed isolated (J-5 T-34 class).
     'LSPL', false, false, false)
ON CONFLICT (id) DO NOTHING;

-- ── 3 crew persons (the 3 role pickers select these) ────────────────────────
-- licence flags pitched to each role so they read plausibly in the picker.
INSERT INTO t_person (id, firstname, lastname, city,
        has_glider_instructor_licence, has_glider_pilot_licence, has_tow_pilot_licence)
VALUES
    ('019e30c3-2c00-7001-8000-0000000000b1', 'Iris', 'Instructor', 'Bern',  true,  true,  false),
    ('019e30c3-2c00-7001-8000-0000000000b2', 'Tom',  'Towpilot',   'Thun',  false, true,  true),
    ('019e30c3-2c00-7001-8000-0000000000b3', 'Fred', 'Flightop',   'Bern',  false, true,  false)
ON CONFLICT (id) DO NOTHING;

-- ── 3 well-known assignment types for seed-club-1 ───────────────────────────
INSERT INTO t_planning_day_assignment_type
    (id, operating_club_id, assignment_type_name, required_nr_of_assignments)
VALUES
    ('019e30c3-2c00-7001-8000-0000000000d1', '019e30c3-2c00-7001-8000-000000000001', 'Segelflugleiter', 1),
    ('019e30c3-2c00-7001-8000-0000000000d2', '019e30c3-2c00-7001-8000-000000000001', 'Schlepppilot',    1),
    ('019e30c3-2c00-7001-8000-0000000000d3', '019e30c3-2c00-7001-8000-000000000001', 'Fluglehrer',      1)
ON CONFLICT (id) DO NOTHING;

-- ── 2 sample future planning days ───────────────────────────────────────────
-- planning_date is relative to first-apply (`CURRENT_DATE + N`) so both days are
-- FUTURE (overview/future = planning_date >= today) when a fresh DB migrates.
--   ...e01 — a weekday day at Bern-Belp, fully crewed (3 assignments below).
--   ...e02 — the next Saturday at Thun, bare (no crew) — exercises the weekend
--            flag on the list (oracle :40-41) and the empty-crew render path.
INSERT INTO t_planning_day (id, operating_club_id, planning_date, location_id, info)
VALUES
    ('019e30c3-2c00-7001-8000-000000000e01',
     '019e30c3-2c00-7001-8000-000000000001',
     CURRENT_DATE + 3,
     '019e30c3-2c00-7001-8000-00000000c001',   -- Bern-Belp
     'Seed planning day — full crew'),
    ('019e30c3-2c00-7001-8000-000000000e02',
     '019e30c3-2c00-7001-8000-000000000001',
     -- next Saturday strictly after today (ISO dow: Sat = 6).
     CURRENT_DATE + ((6 - EXTRACT(ISODOW FROM CURRENT_DATE)::int + 7) % 7
                     + CASE WHEN EXTRACT(ISODOW FROM CURRENT_DATE)::int = 6 THEN 7 ELSE 0 END),
     '019e30c3-2c00-7001-8000-00000000c002',   -- Thun
     'Seed planning day — weekend, no crew')
ON CONFLICT (id) DO NOTHING;

-- ── crew assignments for the weekday day (...e01) ───────────────────────────
-- one row per role, keyed by (person, type). Generic typed-assignment storage —
-- the 3 person pickers project onto these rows by type name (J-6 decision).
INSERT INTO t_planning_day_assignment
    (id, operating_club_id, planning_day_id, assigned_person_id, assignment_type_id)
VALUES
    -- Fluglehrer → Iris Instructor
    ('019e30c3-2c00-7001-8000-000000000f01',
     '019e30c3-2c00-7001-8000-000000000001',
     '019e30c3-2c00-7001-8000-000000000e01',
     '019e30c3-2c00-7001-8000-0000000000b1',
     '019e30c3-2c00-7001-8000-0000000000d3'),
    -- Schlepppilot → Tom Towpilot
    ('019e30c3-2c00-7001-8000-000000000f02',
     '019e30c3-2c00-7001-8000-000000000001',
     '019e30c3-2c00-7001-8000-000000000e01',
     '019e30c3-2c00-7001-8000-0000000000b2',
     '019e30c3-2c00-7001-8000-0000000000d2'),
    -- Segelflugleiter → Fred Flightop
    ('019e30c3-2c00-7001-8000-000000000f03',
     '019e30c3-2c00-7001-8000-000000000001',
     '019e30c3-2c00-7001-8000-000000000e01',
     '019e30c3-2c00-7001-8000-0000000000b3',
     '019e30c3-2c00-7001-8000-0000000000d1')
ON CONFLICT (id) DO NOTHING;
