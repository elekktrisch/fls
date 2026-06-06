-- =============================================================================
-- J-5 T-17 — clean-seed default `AircraftReservationType` for seed-club-1.
--
-- WHY: `t_aircraft_reservation_type` is tenant-scoped reference data populated
-- ONLY by migration — there is deliberately NO create API (a clubadmin
-- masterdata screen is its own future journey). A clean realm club therefore
-- has ZERO reservation types, so the reservation edit form's form-required
-- `reservationTypeId` dropdown is EMPTY → the full clean-seed UI create flow
-- (create → type-picker) cannot run on a clean realm (only on migrated data).
-- Surfaced by the J-5 T-16 real-idp gate spec.
--
-- This seeds ONE active default type ("Allgemein" / general) for seed-club-1
-- (`019e30c3-2c00-7001-8000-000000000001`) — the operating/@TenantId club the
-- J-5 real-idp reservations spec drives as `clubadmin4` (V29 dev-user seed) —
-- so the type dropdown is non-empty and the create→type-picker flow runs
-- end-to-end on clean seed.
--
-- DEV/TEST-SEED, like every V8/V26/V29/V30 dev seed: the row is bound to
-- `seed-club-1`, the canonical dev/test club row from
-- `V5__clubs_walking_skeleton.sql`. IMPORTANT — `seed-club-1` is NOT dev-only:
-- V5 inserts it UNCONDITIONALLY (no profile guard), and Flyway runs the single
-- `classpath:db/migration` location across EVERY profile (no profile split), so
-- seed-club-1 — and therefore THIS reservation-type row — lands wherever the
-- migrations run, production INCLUDED. That is the SAME accepted
-- dev-seed-in-prod posture as the sibling V8/V26/V29/V30 dev seeds: an inert
-- seed-club-1-bound row that real prod tenants (migrated in via `POST
-- /api/v1/clubs`, each a distinct runtime UUID) never see, because the
-- reservation-type read is `@TenantId`-filtered to the operating club. It
-- carries no real reservation-type masterdata; it exists only so the clean-seed
-- test realm has a pickable type. The dev/test guarantee is therefore the
-- @TenantId isolation of the seed-club-1 binding, NOT a "seed-club-1 doesn't
-- exist in prod" claim (it does) and NOT a Flyway location fence — identical to
-- the sibling dev seeds, which carry the same accepted debt. Cleaning up the
-- whole seed-club-1-in-prod posture is a separate cross-cutting decision, not
-- this row's to make.
--
-- The active + not-deleted row appears in `findActiveTypeListItems()` (the
-- `/aircraft-reservation-types` dropdown read), tenant-filtered by @TenantId to
-- seed-club-1. `ON CONFLICT (id) DO NOTHING` mirrors the sibling-seed
-- idempotency. Subsumed by the future reservation-type masterdata screen.
-- =============================================================================

INSERT INTO t_aircraft_reservation_type (
    id,
    operating_club_id,
    reservation_type_name,
    is_instructor_required,
    is_maintenance,
    is_active
) VALUES (
    '019e30c3-2c00-7400-8000-000000000001',
    '019e30c3-2c00-7001-8000-000000000001',  -- seed-club-1
    'Allgemein',
    false,
    false,
    true
)
ON CONFLICT (id) DO NOTHING;
