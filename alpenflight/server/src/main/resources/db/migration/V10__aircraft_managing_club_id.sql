
ALTER TABLE t_aircraft
    ADD COLUMN managing_club_id UUID;

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
