-- J-10 T-03 — rename the delivery tenant-FK constraint to the convention name
-- the leakage guard reconstructs.
--
-- The S-024 leakage sweep (`LeakageSweepIT`) drives a fail-closed write
-- assertion for every `@TenantId` aggregate: a save under the NO_TENANT
-- sentinel must trip the tenant FK, and the test pins the breach to the
-- SPECIFIC constraint name it reconstructs as `fk_<table-stem>_<tenant-col>`
-- (table stem = the `t_`-stripped table name) — here `fk_delivery_operating_club_id`.
--
-- V4 named the delivery tenant FK with an ad-hoc abbreviation
-- (`fk_dlv_operating_club_id`), so the sweep's reconstructed name never matched
-- — the guard couldn't be satisfied for the Delivery aggregate (J-10 T-03)
-- without faking the assertion. Rename in a forward migration (V4 is already
-- applied across the fanout/round-trip environments, so an in-place amend would
-- break Flyway checksum validation there) — the same realignment V41/V43 made
-- for the accounting-rule-filter / delivery-creation-test aggregates.
--
-- Constraint-name-only change — no FK loosened, no column touched. The other
-- delivery FKs (flight_id / recipient_person_id) keep their V4 names; only the
-- tenant-discriminator FK the sweep reconstructs is realigned.
ALTER TABLE t_delivery
    RENAME CONSTRAINT fk_dlv_operating_club_id
        TO fk_delivery_operating_club_id;
