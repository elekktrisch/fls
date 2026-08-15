
ALTER TABLE t_flight DROP CONSTRAINT fk_flight_air_state_id;
ALTER TABLE t_flight DROP COLUMN air_state_id;
ALTER TABLE t_flight ADD COLUMN flight_plan_opened_on TIMESTAMPTZ;

DROP TABLE t_flight_air_state;
