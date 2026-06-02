-- J-1 T-05c — structural counter-unit-type reference-FK resolve.
--
-- The migration AircraftMapper emits its two counter-unit FKs
-- (flight_operating_counter_unit_type_id, engine_operating_counter_unit_type_id)
-- as the synthetic new UUID(0, legacyIntId) encoding
-- (Coercions.optionalLegacyIntIdAsUuidString over the legacy
-- FlightOperatingCounterUnitTypeId / EngineOperatingCounterUnitTypeId). The
-- ingest pipeline's ReferenceLookupResolver rewrites each present value to the
-- real seed PK by joining t_counter_unit_type.legacy_int_id.
--
-- t_counter_unit_type was seeded in V2 WITHOUT a legacy_int_id (V22 backfilled
-- only the elevation/length unit tables), so the resolver had nothing to join
-- on. This migration backfills the column + the unique index the point-lookup
-- relies on, matching the legacy integer keys.
--
-- Legacy CounterUnitTypes static seed (flsserver/database/FLSTest/3 insert/
-- 3 Insert Static Data.sql, lines 128-133) has exactly TWO unit types:
--   1 = 'Minutes' / 'Min'                  -> HOURS_MINUTES (minute-based time)
--   2 = '2 decimals per hour' / '2decimalsperhour' -> HOURS_DECIMAL (decimal hours)
-- (legacy semantics confirmed against CounterUnitExtensions.cs ToCounterValue:
-- "min" totals minutes; "2decimalsperhour" is hours carried to 2 decimals.)
--
-- LANDINGS / STARTS are AlpenFlight-canonical counter kinds with NO legacy
-- CounterUnitTypes equivalent, so they carry NO legacy_int_id — the column is
-- nullable here (diverging from V22's SET NOT NULL, which was valid only because
-- every elevation/length row had a legacy origin). Fabricating a legacy key for
-- a row legacy never had would be a false entry in the reversible id-map. The
-- producer never emits those kinds as a unit-type FK, so the resolver never
-- needs them. The UNIQUE index tolerates the NULLs (Postgres treats NULLs as
-- distinct) while still making the legacy-key join a single-row point lookup.
--
-- Structural only (ADR 0022 directive 2): a reversible id-mapping column + its
-- identity-bearing unique index, no business rule.

ALTER TABLE t_counter_unit_type
    ADD COLUMN legacy_int_id SMALLINT;
UPDATE t_counter_unit_type SET legacy_int_id = 1 WHERE code = 'HOURS_MINUTES';
UPDATE t_counter_unit_type SET legacy_int_id = 2 WHERE code = 'HOURS_DECIMAL';
CREATE UNIQUE INDEX ux_counter_unit_type_legacy_int_id
    ON t_counter_unit_type (legacy_int_id);
