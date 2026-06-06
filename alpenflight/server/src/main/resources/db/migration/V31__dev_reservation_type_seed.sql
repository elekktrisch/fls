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
-- DEV/TEST-ONLY, like every V8/V26/V28/V29/V30 dev seed: the row is bound to
-- `seed-club-1`, the canonical dev/test club row from
-- `V5__clubs_walking_skeleton.sql`. A production deployment is migrated into a
-- club created via the real `POST /api/v1/clubs` surface (a distinct, runtime
-- UUID) — seed-club-1 never exists there, so this row simply has no tenant to
-- attach to in prod. It carries no real reservation-type masterdata; it exists
-- only so the clean-seed test realm has a pickable type. The Flyway location is
-- the single `classpath:db/migration` for every profile (no profile split), so
-- the dev/test-only guarantee is the seed-club-1 binding, NOT a location fence
-- — identical to the sibling dev seeds.
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
