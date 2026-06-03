-- S-061 — net-new `locked_at` column on flight (J-2 time-gate).
--
-- Records when a flight transitioned Valid -> Locked. DELIBERATE
-- divergence from legacy (J-2 parity decision, operator 2026-06-03):
-- legacy keys both the lock and bill gates on CreatedOn and has no
-- locked_at column. The rewrite locks on `flight_date <= today-2d` and
-- bills on `locked_at <= today-3d`. `created_on` stays for audit but no
-- longer drives the gates.
--
-- Per ADR 0022 directive 2 this is purely structural — nullable, no
-- CHECK / trigger / generated-column. The Valid->Locked stamping and the
-- date-gate comparison are business rules carried by the Flight
-- aggregate + FlightGatePolicy (Java), never the schema.
ALTER TABLE t_flight
    ADD COLUMN locked_at TIMESTAMPTZ;
