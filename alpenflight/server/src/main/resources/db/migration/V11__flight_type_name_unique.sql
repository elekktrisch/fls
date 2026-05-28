-- V11__flight_type_name_unique.sql
--
-- S-053: add identity-bearing partial UNIQUE on (operating_club_id,
-- flight_type_name) WHERE deleted_on IS NULL. Mirrors the V3
-- ux_flight_type_club_code shape (on flight_code) — the name is the
-- human-facing identity, the code is the optional short id.
--
-- Soft-deleted rows are filtered out so a club can soft-delete a
-- flight type and create a new one with the same name (matches the
-- S-050 Aircraft "soft-delete-then-recreate-same-name" pattern).
--
-- Per ADR 0022 directive 2: this is a structural identity invariant
-- (two active rows under the same tenant cannot share the same
-- visible name), not a business rule.

CREATE UNIQUE INDEX ux_flight_type_club_name
    ON t_flight_type (operating_club_id, flight_type_name)
    WHERE deleted_on IS NULL;
