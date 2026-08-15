
ALTER TABLE t_location ADD COLUMN legacy_guid UUID;

COMMENT ON COLUMN t_location.legacy_guid IS
    'Shared legacy LocationId (MSSQL Locations.LocationId). IDENTICAL across all '
    'fan-out replicas of one legacy Location — distinct from the per-replica '
    'derived `id` (uuidv5 of legacy_guid + legacy club_id, J-0b). NULL for rows '
    'with no legacy origin (clean-seed + API-created). Populated only by migration ingest.';

CREATE UNIQUE INDEX ux_location_legacy_guid_club
    ON t_location (legacy_guid, club_id)
    WHERE legacy_guid IS NOT NULL AND deleted_on IS NULL;
