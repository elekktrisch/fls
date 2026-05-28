-- V10__aircraft_managing_club_id.sql
--
-- S-159: Aircraft becomes structurally tenant-scoped via a NEW
-- `managing_club_id` column. Separate from `owner_club_id` (the physical
-- owner — own club, other club, or NULL). Every aircraft has exactly one
-- managing tenant (the club that registered + operates it). Ownership is
-- independent metadata.
--
-- Per ADR 0022 directive 2: schema is structural only (PK / FK / NOT NULL
-- / index). Owner-kind discriminator (own-club vs. external org vs. private
-- person) is derived at the domain layer, not encoded in a CHECK or enum.

ALTER TABLE t_aircraft
    ADD COLUMN managing_club_id UUID;

-- Backfill: if the aircraft already had an owner_club_id, that club becomes
-- the managing club (own-club case). For pre-existing charter / null-owner
-- rows, assign seed-club-1 — the only club guaranteed to exist by V5. Any
-- legacy-data import (S-028+) will re-assign managing_club_id per source DB.
UPDATE t_aircraft
SET managing_club_id = COALESCE(owner_club_id,
                                '019e30c3-2c00-7001-8000-000000000001'::uuid);

ALTER TABLE t_aircraft
    ALTER COLUMN managing_club_id SET NOT NULL,
    ADD CONSTRAINT fk_aircraft_managing_club_id
        FOREIGN KEY (managing_club_id) REFERENCES t_club (id) ON DELETE RESTRICT;

CREATE INDEX ix_aircraft_managing_club ON t_aircraft (managing_club_id);

COMMENT ON COLUMN t_aircraft.managing_club_id IS
    'The tenant (club) that registered + operates this aircraft. Hibernate @TenantId discriminator (S-159). Distinct from owner_club_id (the physical owner, which may be a different club or NULL).';
COMMENT ON COLUMN t_aircraft.owner_club_id IS
    'Physical owner (club) of the aircraft. NULL when owned by a private person (see aircraft_owner_person_id) or by an external organisation not in the Clubs catalog. Equals managing_club_id in the own-club case.';
