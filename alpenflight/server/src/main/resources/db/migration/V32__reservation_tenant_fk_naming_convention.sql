-- J-5 T-29 — rename the two reservation tenant-FK constraints to the
-- convention name the leakage guard reconstructs.
--
-- The S-024 leakage sweep (`LeakageSweepIT`) drives a fail-closed write
-- assertion for every `@TenantId` aggregate: a save under the NO_TENANT
-- sentinel must trip the tenant FK, and the test pins the breach to the
-- SPECIFIC constraint name it reconstructs as `fk_<table-stem>_<tenant-col>`
-- (table stem = the `t_`-stripped table name). Every other tenant-scoped
-- table follows that shape (`fk_flight_operating_club_id`,
-- `fk_location_club_id`, `fk_article_operating_club_id`, …).
--
-- V4 named the two reservation tenant FKs with an ad-hoc abbreviation
-- (`fk_arv_operating_club_id` / `fk_arvt_operating_club_id`), so the sweep's
-- reconstructed name never matched — the guard couldn't be satisfied for the
-- reservation aggregates without faking the assertion. Rename in a forward
-- migration (V4 is already applied across the fanout/round-trip environments,
-- so an in-place amend would break Flyway checksum validation there).
--
-- Constraint-name-only change — no FK loosened, no column touched. The other
-- reservation FKs (aircraft/person/location/type/flight_type) keep their V4
-- names; only the tenant-discriminator FK the sweep reconstructs is realigned.
ALTER TABLE t_aircraft_reservation_type
    RENAME CONSTRAINT fk_arvt_operating_club_id
        TO fk_aircraft_reservation_type_operating_club_id;

ALTER TABLE t_aircraft_reservation
    RENAME CONSTRAINT fk_arv_operating_club_id
        TO fk_aircraft_reservation_operating_club_id;
