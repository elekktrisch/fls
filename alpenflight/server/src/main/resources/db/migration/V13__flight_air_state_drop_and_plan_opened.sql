-- S-060 — air state is computed, never stored.
--
-- Drop the carried-over storage:
--   * flight.air_state_id (column + FK + NOT NULL).
--   * flight_air_state reference table.
--   * legacy `AirStateId == FlightPlanOpen` branch replaced by a structural
--     timestamp column flight.flight_plan_opened_on (nullable). The setting
--     workflow is a future flight-plan-open story; S-060 only adds the column
--     + the airState() compute branch that reads it.
--
-- Per ADR 0022 directive 2 air-state computation lives on the Flight
-- aggregate (Flight.airState()), not in the schema — no generated column,
-- no trigger, no CHECK.

ALTER TABLE flight DROP CONSTRAINT fk_flight_air_state_id;
ALTER TABLE flight DROP COLUMN air_state_id;
ALTER TABLE flight ADD COLUMN flight_plan_opened_on TIMESTAMPTZ;

DROP TABLE flight_air_state;
