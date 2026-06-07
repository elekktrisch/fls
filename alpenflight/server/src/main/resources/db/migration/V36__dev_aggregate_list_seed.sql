-- =============================================================================
-- J-6b T-14 — dev-seed sweep: ≥1 row for EVERY club-owned aggregate LIST a
-- testuser can open, for seed-club-1.
--
-- FOUND EMPIRICALLY (operator 2026-06-07: "instead of analytical enumeration,
-- just seed the db and do the selects to check the truth"). A clean Flyway-only
-- migrate (exactly what e2e / CI / a fresh dev bring-up gets — the showcase
-- harness is `@Profile("showcase")` and does NOT run in plain dev) was swept with
-- a `SELECT count(*) … WHERE <club_col> = seed-club-1` per `t_*` aggregate table.
-- Every testuser (clubadmin1..4, pilot1, pilot-empty1) is bound to seed-club-1
-- (V8/V26/V28/V29/V30), so the matrix is each aggregate × seed-club-1.
--
-- The sweep's EMPTY cells with a user-facing per-club LIST screen (the operator's
-- "EVERY aggregate list has ≥1 row" AC) were:
--   t_member_state         (persons → member-state picker / masterdata)
--   t_person_club          (Persons list — the symptom the operator saw)
--   t_aircraft             (Aircraft list)
--   t_flight_type          (Flight-types list)
--   t_article              (Articles list)
--   t_flight               (Flights list)
--   t_aircraft_reservation (Reservations list/calendar)
-- This migration seeds exactly one realistic row each. (Reservation-type and the
-- planning aggregates were already non-empty — V31 / V34 — so they are NOT
-- re-seeded here.)
--
-- LEGITIMATELY EMPTY (no per-club user-facing list a testuser opens — sysadmin /
-- global / future-journey / child-of-an-already-seeded parent), left empty by
-- design, NOT a gap:
--   t_accounting_rule_filter, t_delivery, t_delivery_item,
--   t_delivery_creation_test(_item), t_club_delivery_number_counter,
--   t_club_extension, t_extension_value, t_email_template,
--   t_person_category, t_person_category_assignment   — no FE list feature
--   t_deployment                                       — sysadmin/cutover, not club-owned masterdata
-- (Pure reference data — t_country/t_language/t_*_unit_type/t_*_state/… — is global,
-- not club-scoped, and seeded structurally by V2/V3.)
--
-- DEV/TEST-SEED — identical accepted posture to the sibling V8/V26/V28/V29/V30/
-- V31/V34 dev seeds (read V34's header): every row is bound to `seed-club-1`
-- (`019e30c3-2c00-7001-8000-000000000001`, the canonical dev/test club from
-- V5__clubs_walking_skeleton.sql). Flyway runs the single `classpath:db/migration`
-- location across EVERY profile, so these rows land wherever the migrations run,
-- production INCLUDED — but INERT there: every list read is `@TenantId`-filtered
-- to the operating club, and real prod tenants are migrated in via
-- `POST /api/v1/clubs` as distinct runtime UUIDs that never see seed-club-1's rows.
--
-- SEED-BAND ids: every PK is in the `019e30c3-2c00-7001-8000-...` seed band, so
-- the controller-IT pre-clean guards that scope to `id NOT LIKE '019e30c3-%'`
-- spare these rows AND their exact-count assertions ignore them (J-5 T-34 /
-- V34 lesson). The controller ITs that count these tables either (a) DELETE the
-- whole table for seed-club-1 unconditionally in @BeforeEach and rebuild
-- (flights/articles/flight-types/reservations) or (b) assert by finding their
-- own row, never an exact list size (aircraft/persons) — verified before adding.
-- Reuses V34's locations (...c001/...c002) + persons (...b1/...b2/...b3), V31's
-- reservation type (...7400...001) and the global reference data. Idempotent on
-- the fixed PK (`ON CONFLICT (id) DO NOTHING`).
-- =============================================================================

-- ── member states (Persons → member-state masterdata + person_club FK) ───────
INSERT INTO t_member_state (id, club_id, name)
VALUES
    ('019e30c3-2c00-7001-8000-0000000000a1', '019e30c3-2c00-7001-8000-000000000001', 'Aktivmitglied'),
    ('019e30c3-2c00-7001-8000-0000000000a2', '019e30c3-2c00-7001-8000-000000000001', 'Passivmitglied')
ON CONFLICT (id) DO NOTHING;

-- ── person_club memberships (the Persons LIST — operator's empty-list symptom) ─
-- Link the 3 V34 planning persons into seed-club-1 so GET /api/v1/persons (which
-- reads FROM PersonClub, @TenantId-scoped) returns rows. Active members so they
-- show in the active list; the licence-role flags mirror each person's V34 role.
INSERT INTO t_person_club
    (id, person_id, club_id, member_number, member_state_id,
     is_glider_instructor, is_glider_pilot, is_tow_pilot, is_active)
VALUES
    ('019e30c3-2c00-7001-8000-0000000000c1', '019e30c3-2c00-7001-8000-0000000000b1',
     '019e30c3-2c00-7001-8000-000000000001', '1001',
     '019e30c3-2c00-7001-8000-0000000000a1', true,  true,  false, true),
    ('019e30c3-2c00-7001-8000-0000000000c2', '019e30c3-2c00-7001-8000-0000000000b2',
     '019e30c3-2c00-7001-8000-000000000001', '1002',
     '019e30c3-2c00-7001-8000-0000000000a1', false, true,  true,  true),
    ('019e30c3-2c00-7001-8000-0000000000c3', '019e30c3-2c00-7001-8000-0000000000b3',
     '019e30c3-2c00-7001-8000-000000000001', '1003',
     '019e30c3-2c00-7001-8000-0000000000a1', false, true,  false, true)
ON CONFLICT (id) DO NOTHING;

-- ── aircraft (the Aircraft LIST) ─────────────────────────────────────────────
-- A glider (aircraft_type GLIDER, V3 ref) managed by seed-club-1. immatriculation
-- is regulator-globally-unique; "HB-SEED" is reserved for this dev row and does
-- NOT collide with any IT fixture (HB-RV%/HB-FT%/HB-X%/faker).
INSERT INTO t_aircraft
    (id, managing_club_id, owner_club_id, aircraft_type_id,
     manufacturer_name, aircraft_model, immatriculation, competition_sign, nr_of_seats,
     is_towing_or_winch_required, is_towing_start_allowed, is_winch_start_allowed,
     is_towing_aircraft, is_fast_entry_record)
VALUES
    ('019e30c3-2c00-7001-8000-000000000a10',
     '019e30c3-2c00-7001-8000-000000000001',
     '019e30c3-2c00-7001-8000-000000000001',
     '019e2e15-2c00-7af9-8000-000000002af9',   -- GLIDER (V3 aircraft_type)
     'Schleicher', 'ASK-21', 'HB-SEED', 'SD', 2,
     true, false, false, false, false)
ON CONFLICT (id) DO NOTHING;

-- Opening airworthiness state period (OK, V3 aircraft_state ref) so the aircraft
-- list's left-joined current-state column renders a value — matches the
-- ShowcaseSeeder convention (exactly one open period; valid_to NULL).
INSERT INTO t_aircraft_aircraft_state
    (id, aircraft_id, aircraft_state_id, valid_from, valid_to)
VALUES
    ('019e30c3-2c00-7001-8000-000000000a11',
     '019e30c3-2c00-7001-8000-000000000a10',
     '019e2e15-2c00-7ee0-8000-000000002ee0',   -- OK
     now(), NULL)
ON CONFLICT (id) DO NOTHING;

-- ── flight type (the Flight-types LIST) ──────────────────────────────────────
INSERT INTO t_flight_type
    (id, operating_club_id, flight_type_name, flight_code, is_for_glider_flights)
VALUES
    ('019e30c3-2c00-7001-8000-000000000f10',
     '019e30c3-2c00-7001-8000-000000000001',
     'Schulung', 'SCH', true)
ON CONFLICT (id) DO NOTHING;

-- ── article (the Articles LIST) ──────────────────────────────────────────────
INSERT INTO t_article
    (id, operating_club_id, article_number, article_name, article_info, is_active)
VALUES
    ('019e30c3-2c00-7001-8000-000000000a20',
     '019e30c3-2c00-7001-8000-000000000001',
     'A-1001', 'Startgebühr Segelflug', 'Seed article', true)
ON CONFLICT (id) DO NOTHING;

-- ── flight (the Flights LIST) ────────────────────────────────────────────────
-- One past glider flight on the seeded aircraft, crewed by Iris Instructor (V34
-- person ...b1). start/ldg at Bern-Belp (V34 location ...c001),
-- flight_aircraft_type_id = 1 (GLIDER legacy id).
--
-- SEED-ISOLATION (J-6b T-21): process_state is VALID (V2 ref, legacy 30) — a
-- NON-pending terminal state — NOT NotProcessed/Invalid. The J-3 dashboard's
-- pending-validation tile counts exactly {NotProcessed, Invalid}
-- (FlightsService#clubFlightCounts → countByProcessStateIdIn) for seed-club-1,
-- and seed-club-1 is ALSO the deterministic showcase fixture club that
-- start-dashboard.spec.ts asserts pending=4 on. A NotProcessed seed flight here
-- leaked into that exact count (4 → 5). VALID keeps this row off the pending set
-- while still populating the Flights LIST (T-14 AC #6 — the row still exists).
-- today-flights is unaffected regardless: flight_date is CURRENT_DATE-7, not
-- today. VALID needs no companion field — validated_on/locked_at stay NULL
-- (both @Nullable; no DB CHECK ties state↔timestamp per ADR 0022 D2; the
-- timestamp is only required on a runtime Valid→Locked transition, not at rest).
INSERT INTO t_flight
    (id, operating_club_id, aircraft_id, flight_date,
     start_date_time, ldg_date_time, start_location_id, ldg_location_id,
     flight_type_id, start_type_id, is_solo_flight,
     no_start_time_information, no_ldg_time_information,
     process_state_id, flight_aircraft_type_id, comment)
VALUES
    ('019e30c3-2c00-7001-8000-000000000f20',
     '019e30c3-2c00-7001-8000-000000000001',
     '019e30c3-2c00-7001-8000-000000000a10',
     CURRENT_DATE - 7,
     (CURRENT_DATE - 7 + TIME '10:00')::timestamptz,
     (CURRENT_DATE - 7 + TIME '11:00')::timestamptz,
     '019e30c3-2c00-7001-8000-00000000c001',   -- Bern-Belp (V34)
     '019e30c3-2c00-7001-8000-00000000c001',
     '019e30c3-2c00-7001-8000-000000000f10',   -- the seeded flight type
     '019e2e15-2c00-7fa0-8000-000000000fa0',   -- Winch Launch (V2 start_type)
     false, false, false,
     '019e2e15-2c00-7a9a-8000-000000003a9a',   -- VALID (V2 process_state, legacy 30) — non-pending; T-21
     1,                                         -- GLIDER (FlightAircraftType.legacyId)
     'Seed flight — glider')
ON CONFLICT (id) DO NOTHING;

-- One PIC crew row so the flight reads as crewed (PILOT_OR_STUDENT, V2 ref).
INSERT INTO t_flight_crew
    (id, flight_id, person_id, flight_crew_type_id,
     begin_flight_datetime, end_flight_datetime, nr_of_ldgs, nr_of_starts)
VALUES
    ('019e30c3-2c00-7001-8000-000000000f21',
     '019e30c3-2c00-7001-8000-000000000f20',
     '019e30c3-2c00-7001-8000-0000000000b1',   -- Iris Instructor (V34)
     '019e2e15-2c00-76b0-8000-0000000036b0',   -- PILOT_OR_STUDENT
     (CURRENT_DATE - 7 + TIME '10:00')::timestamptz,
     (CURRENT_DATE - 7 + TIME '11:00')::timestamptz,
     1, 1)
ON CONFLICT (id) DO NOTHING;

-- ── aircraft reservation (the Reservations LIST / calendar) ──────────────────
-- A future booking on the seeded aircraft (reservation_range is a GENERATED
-- column — derived from start/end, must NOT be inserted). Pilot = Iris (V34),
-- location = Bern-Belp (V34), type = Allgemein (V31).
INSERT INTO t_aircraft_reservation
    (id, operating_club_id, aircraft_id, reservation_start, reservation_end,
     is_all_day, pilot_person_id, location_id, reservation_type_id, info)
VALUES
    ('019e30c3-2c00-7001-8000-000000000a30',
     '019e30c3-2c00-7001-8000-000000000001',
     '019e30c3-2c00-7001-8000-000000000a10',
     (CURRENT_DATE + 2 + TIME '09:00')::timestamptz,
     (CURRENT_DATE + 2 + TIME '12:00')::timestamptz,
     false,
     '019e30c3-2c00-7001-8000-0000000000b1',   -- Iris Instructor (V34)
     '019e30c3-2c00-7001-8000-00000000c001',   -- Bern-Belp (V34)
     '019e30c3-2c00-7400-8000-000000000001',   -- Allgemein (V31)
     'Seed reservation')
ON CONFLICT (id) DO NOTHING;
