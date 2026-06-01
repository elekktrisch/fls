-- =============================================================================
-- J-0b T-05b: t_inoutbound_point.legacy_guid (child fan-out column).
--
-- Discovered during T-05: the child InOutboundPoint is now a fan-out entity
-- (it carries its own legacy club on the wire and fans out one row per
-- (legacy IOP, legacy club), attaching to the matching parent Location
-- replica). T-06's de-aliasing therefore emits BOTH `legacy_guid` (verbatim,
-- the shared legacy InOutboundPoint id) AND a distinct derived `id` as separate
-- destination columns. `t_inoutbound_point` (V3:608-623) had no `legacy_guid`
-- column, so that INSERT would fail.
--
-- This mirrors V23 (t_location.legacy_guid) for the child. Unlike the parent,
-- the child has NO own `club_id` column — its tenancy is per parent replica, so
-- the identity UNIQUE keys on (legacy_guid, location_id): location_id is the
-- per-club Location replica id, the right second key for the child's identity.
--
-- Per ADR 0022 directive 2: schema enforces structural identity only. The
-- (legacy_guid, location_id) partial UNIQUE is an identity-bearing structural
-- invariant (one child replica per shared-legacy-IOP / parent-Location-replica)
-- — NOT a business rule. No CHECKs, no triggers; fan-out keying logic lives in
-- the producer / ingest pipeline.
-- =============================================================================

-- Nullable: clean-seed rows and API-created rows have no legacy origin; only
-- migrated rows populate it. NOT NULL would break both non-migration paths.
ALTER TABLE t_inoutbound_point ADD COLUMN legacy_guid UUID;

COMMENT ON COLUMN t_inoutbound_point.legacy_guid IS
    'Shared legacy InOutboundPoint id. IDENTICAL across all fan-out replicas of '
    'one legacy IOP — distinct from the per-replica derived `id` (J-0b fan-out). '
    'NULL for rows with no legacy origin (clean-seed + API-created). Populated '
    'only by migration ingest.';

-- Identity-bearing partial UNIQUE: exactly one current (non-soft-deleted) child
-- replica per (shared legacy IOP, parent Location replica). The child has no own
-- `club_id`; `location_id` is the per-club Location replica id, so it is the
-- correct per-replica identity key. Excludes soft-deleted rows
-- (deleted_on IS NULL) and non-migrated rows (legacy_guid IS NULL), matching the
-- V23 ux_location_legacy_guid_club convention.
CREATE UNIQUE INDEX ux_iop_legacy_guid_location
    ON t_inoutbound_point (legacy_guid, location_id)
    WHERE legacy_guid IS NOT NULL AND deleted_on IS NULL;
