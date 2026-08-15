
ALTER TABLE t_inoutbound_point ADD COLUMN legacy_guid UUID;

COMMENT ON COLUMN t_inoutbound_point.legacy_guid IS
    'Shared legacy InOutboundPoint id. IDENTICAL across all fan-out replicas of '
    'one legacy IOP — distinct from the per-replica derived `id` (J-0b fan-out). '
    'NULL for rows with no legacy origin (clean-seed + API-created). Populated '
    'only by migration ingest.';

CREATE UNIQUE INDEX ux_iop_legacy_guid_location
    ON t_inoutbound_point (legacy_guid, location_id)
    WHERE legacy_guid IS NOT NULL AND deleted_on IS NULL;
