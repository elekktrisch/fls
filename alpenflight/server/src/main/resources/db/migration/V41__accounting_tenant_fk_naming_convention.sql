-- J-8 T-04 — rename the accounting-rule-filter tenant-FK constraint to the
-- convention name the leakage guard reconstructs.
--
-- The S-024 leakage sweep (`LeakageSweepIT`) drives a fail-closed write
-- assertion for every `@TenantId` aggregate: a save under the NO_TENANT
-- sentinel must trip the tenant FK, and the test pins the breach to the
-- SPECIFIC constraint name it reconstructs as `fk_<table-stem>_<tenant-col>`
-- (table stem = the `t_`-stripped table name). Every other tenant-scoped
-- table follows that shape (`fk_aircraft_reservation_operating_club_id`,
-- `fk_planning_day_operating_club_id`, `fk_flight_operating_club_id`, …).
--
-- V4 named the accounting tenant FK with an ad-hoc abbreviation
-- (`fk_arf_operating_club_id`), so the sweep's reconstructed name never matched
-- — the guard couldn't be satisfied for the AccountingRuleFilter aggregate
-- (J-8 T-03) without faking the assertion. Rename in a forward migration (V4 is
-- already applied across the fanout/round-trip environments, so an in-place
-- amend would break Flyway checksum validation there) — the same realignment
-- V32/V33 made for the reservation/planning aggregates.
--
-- Constraint-name-only change — no FK loosened, no column touched. The other
-- accounting FKs (filter_type / accounting_unit_type) keep their V4 names; only
-- the tenant-discriminator FK the sweep reconstructs is realigned.
ALTER TABLE t_accounting_rule_filter
    RENAME CONSTRAINT fk_arf_operating_club_id
        TO fk_accounting_rule_filter_operating_club_id;
