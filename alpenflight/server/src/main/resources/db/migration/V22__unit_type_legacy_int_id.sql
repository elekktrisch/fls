
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
