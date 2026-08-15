
ALTER TABLE t_counter_unit_type
    ADD COLUMN legacy_int_id SMALLINT;
UPDATE t_counter_unit_type SET legacy_int_id = 1 WHERE code = 'HOURS_MINUTES';
UPDATE t_counter_unit_type SET legacy_int_id = 2 WHERE code = 'HOURS_DECIMAL';
CREATE UNIQUE INDEX ux_counter_unit_type_legacy_int_id
    ON t_counter_unit_type (legacy_int_id);
