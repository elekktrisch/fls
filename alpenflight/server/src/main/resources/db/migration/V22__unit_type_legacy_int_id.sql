-- J-0 T-02a — structural FLIGHT-group reference-FK resolve.
--
-- The migration LocationMapper emits its lookup FKs (location_type_id,
-- elevation_unit_type_id, runway_length_unit_type_id) as the synthetic
-- new UUID(0, legacyIntId) encoding (Coercions.legacyIntIdToUuidString). The
-- ingest pipeline's ReferenceLookupResolver rewrites each to the real seed PK
-- by joining the seed table's legacy_int_id column.
--
-- t_location_type already carries legacy_int_id + ux_location_type_legacy_int_id
-- (V3). The two unit-type tables were seeded in V2 WITHOUT a legacy_int_id, so
-- the resolver had nothing to join on for elevation_unit_type_id /
-- runway_length_unit_type_id. This migration backfills the column + the unique
-- index the point-lookup relies on, matching the legacy integer keys:
--   ElevationUnitTypes / LengthUnitTypes: Meter = 1, Feet = 2
-- (legacy FLSTest static seed; the V2 rows are seeded METER first, FEET second).
--
-- Structural only (ADR 0022 directive 2): a reversible id-mapping column + its
-- identity-bearing unique index, no business rule.

ALTER TABLE t_elevation_unit_type
    ADD COLUMN legacy_int_id SMALLINT;
UPDATE t_elevation_unit_type SET legacy_int_id = 1 WHERE code = 'METER';
UPDATE t_elevation_unit_type SET legacy_int_id = 2 WHERE code = 'FEET';
ALTER TABLE t_elevation_unit_type
    ALTER COLUMN legacy_int_id SET NOT NULL;
CREATE UNIQUE INDEX ux_elevation_unit_type_legacy_int_id
    ON t_elevation_unit_type (legacy_int_id);

ALTER TABLE t_length_unit_type
    ADD COLUMN legacy_int_id SMALLINT;
UPDATE t_length_unit_type SET legacy_int_id = 1 WHERE code = 'METER';
UPDATE t_length_unit_type SET legacy_int_id = 2 WHERE code = 'FEET';
ALTER TABLE t_length_unit_type
    ALTER COLUMN legacy_int_id SET NOT NULL;
CREATE UNIQUE INDEX ux_length_unit_type_legacy_int_id
    ON t_length_unit_type (legacy_int_id);
