
ALTER TABLE t_location ADD COLUMN club_id UUID;
UPDATE t_location SET club_id = '019e30c3-2c00-7001-8000-000000000001' WHERE club_id IS NULL;
ALTER TABLE t_location ALTER COLUMN club_id SET NOT NULL;
ALTER TABLE t_location ADD CONSTRAINT fk_location_club_id
    FOREIGN KEY (club_id) REFERENCES t_club (id) ON DELETE RESTRICT;

DROP INDEX ux_location_icao;
CREATE UNIQUE INDEX ux_location_club_icao
    ON t_location (club_id, icao_code)
    WHERE icao_code IS NOT NULL AND deleted_on IS NULL;

CREATE INDEX ix_location_club
    ON t_location (club_id)
    WHERE deleted_on IS NULL;
