-- J-6 T-03 — rename the two planning tenant-FK constraints to the convention
-- name the leakage guard reconstructs (mirrors V32 for the reservation FKs).
--
-- The S-024 leakage sweep (`LeakageSweepIT`) drives a fail-closed write
-- assertion for every `@TenantId` aggregate: a save under the NO_TENANT
-- sentinel must trip the tenant FK, and the test pins the breach to the
-- SPECIFIC constraint name it reconstructs as `fk_<table-stem>_<tenant-col>`
-- (table stem = the `t_`-stripped table name). Every other tenant-scoped
-- table follows that shape (`fk_aircraft_reservation_operating_club_id`,
-- `fk_flight_operating_club_id`, `fk_location_club_id`, …).
--
-- V4 named the two planning tenant FKs with an ad-hoc abbreviation
-- (`fk_pln_operating_club_id` / `fk_pdat_operating_club_id`), so the sweep's
-- reconstructed name (`fk_planning_day_operating_club_id` /
-- `fk_planning_day_assignment_type_operating_club_id`) never matched — the
-- guard couldn't be satisfied for the planning aggregates without faking the
-- assertion. Rename in a forward migration (V4 is already applied across the
-- fanout/round-trip environments, so an in-place amend would break Flyway
-- checksum validation there).
--
-- Constraint-name-only change — no FK loosened, no column touched. The other
-- planning FKs (location/person/day/type) keep their V4 names; only the
-- tenant-discriminator FK the sweep reconstructs is realigned.
ALTER TABLE t_planning_day_assignment_type
    RENAME CONSTRAINT fk_pdat_operating_club_id
        TO fk_planning_day_assignment_type_operating_club_id;

ALTER TABLE t_planning_day
    RENAME CONSTRAINT fk_pln_operating_club_id
        TO fk_planning_day_operating_club_id;
