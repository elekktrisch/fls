-- =============================================================================
-- J-0b T-02: t_location.legacy_guid + composite identity UNIQUE (fan-out keying).
--
-- Legacy tenant-scoped masterdata is SHARED — one legacy `Locations` row is
-- referenced by many clubs — but the new stack is tenant-partitioned
-- (`t_location.club_id` per ADR 0008). One legacy Location therefore fans out
-- into N `t_location` rows, one per referencing club, each with a DISTINCT,
-- app-minted `id` (the derived `uuidv5(legacy_guid + legacy club_id)` of J-0b).
--
-- This migration adds the column that holds the SHARED legacy LocationId
-- (identical across every fan-out replica; distinct from each replica's derived
-- `id`) and the structural identity invariant that guarantees exactly one
-- replica per (legacy Location, club).
--
-- Per ADR 0022 directive 2: schema enforces structural identity only. The
-- (legacy_guid, club_id) partial UNIQUE is an identity-bearing structural
-- invariant (one row per shared-legacy-Location/club replica) — NOT a business
-- rule. No CHECKs, no triggers; fan-out keying logic lives in the producer /
-- ingest pipeline.
-- =============================================================================

-- Nullable: clean-seed rows and API-created rows have no legacy origin; only
-- migrated rows populate it. NOT NULL would break both non-migration paths.
ALTER TABLE t_location ADD COLUMN legacy_guid UUID;

COMMENT ON COLUMN t_location.legacy_guid IS
    'Shared legacy LocationId (MSSQL Locations.LocationId). IDENTICAL across all '
    'fan-out replicas of one legacy Location — distinct from the per-replica '
    'derived `id` (uuidv5 of legacy_guid + legacy club_id, J-0b). NULL for rows '
    'with no legacy origin (clean-seed + API-created). Populated only by migration ingest.';

-- Identity-bearing partial UNIQUE: exactly one current (non-soft-deleted)
-- t_location replica per (shared legacy Location, club). This is the structural
-- guarantee the fan-out keying relies on — re-ingest UPSERTs onto this index
-- rather than colliding on the `id` PK (the J-0 23505 failure). Excludes
-- soft-deleted rows (deleted_on IS NULL) and non-migrated rows
-- (legacy_guid IS NULL), matching the V7 ux_location_club_icao convention.
CREATE UNIQUE INDEX ux_location_legacy_guid_club
    ON t_location (legacy_guid, club_id)
    WHERE legacy_guid IS NOT NULL AND deleted_on IS NULL;
