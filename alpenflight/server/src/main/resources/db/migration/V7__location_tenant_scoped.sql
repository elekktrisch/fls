-- =============================================================================
-- S-049b: Locations become tenant-scoped masterdata.
--
-- Reclassifies `location` from CROSS_TENANT reference data (V3's design) to
-- TENANT_SCOPED masterdata. Same physical airport (e.g. LSZH) may now exist
-- multiple times across clubs but only once per club. `inoutbound_point`
-- inherits tenancy through its parent `location` (the FK chain carries it —
-- no own `club_id` column, same pattern as `flight_crew` under `flight`).
--
-- Pre-cutover: no production rows exist. V3 does not seed `location` rows;
-- the backfill is defensive only and targets the canonical V5 dev seed club.
--
-- Per ADR 0022 directive 2: schema enforces structural invariants only. The
-- "homebase of club X must be a Location owned by X" rule is a domain
-- invariant on the Club aggregate, not enforced here.
-- =============================================================================

-- 1. Add club_id (nullable for backfill), backfill, promote to NOT NULL, FK.
--    The backfill UUID references the V5 dev-seed club
--    (V5__clubs_walking_skeleton.sql:32) — the only club row that may exist
--    pre-S-049b. The branch is defensive: V3 does not seed location rows,
--    so a fresh deployment hits zero affected rows.
ALTER TABLE location ADD COLUMN club_id UUID;
UPDATE location SET club_id = '019e30c3-2c00-7001-8000-000000000001' WHERE club_id IS NULL;
ALTER TABLE location ALTER COLUMN club_id SET NOT NULL;
ALTER TABLE location ADD CONSTRAINT fk_location_club_id
    FOREIGN KEY (club_id) REFERENCES club (id) ON DELETE RESTRICT;

-- 2. Drop the global ICAO uniqueness; replace with per-club partial UNIQUE.
--    The new index also excludes soft-deleted rows, retiring the S-049
--    "null out icao_code on soft-delete" workaround (which sacrificed audit
--    fidelity for recreate-after-soft-delete semantics). Same-ICAO-after-
--    soft-delete-same-club still recreates because the predicate filters
--    deleted rows out of the uniqueness scope.
DROP INDEX ux_location_icao;
CREATE UNIQUE INDEX ux_location_club_icao
    ON location (club_id, icao_code)
    WHERE icao_code IS NOT NULL AND deleted_on IS NULL;

-- 3. Discriminator-filter index for the @TenantId predicate Hibernate
--    appends on every JPA query against the Location aggregate.
CREATE INDEX ix_location_club
    ON location (club_id)
    WHERE deleted_on IS NULL;
