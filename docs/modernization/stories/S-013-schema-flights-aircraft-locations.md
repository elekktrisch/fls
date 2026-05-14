---
id: S-013
title: V1__baseline part 2 — flights / aircraft / persons / clubs / locations
epic: E-02
status: todo
depends_on: [S-012]
acceptance:
  - Tables defined: `flight`, `flight_crew`, `flight_crew_type`, `flight_type`, `flight_cost_balance_type`, `aircraft`, `aircraft_type`, `aircraft_state`, `aircraft_aircraft_state`, `aircraft_operating_counter`, `article`, `location`, `location_type`, `inoutbound_point`.
  - `flight.tow_flight_id` self-FK to `flight.id` present.
  - `flight.operating_club_id` (tenant) FK and indexed.
  - `flight.glider_pilot_person_id` / `flight_instructor_person_id` / `tow_pilot_person_id` FKs to `person.id` — cross-tenant by design.
  - `flight_process_state_id` and `flight_aircraft_type_id` columns (enums modeled as small lookup tables or smallint per ADR-implementation choice).
  - Indexes on hot-path columns: `(operating_club_id, flight_date)`, `(flight_process_state_id, operating_club_id)`, `tow_flight_id`.
estimate: L
adr_refs: [0002, 0003, 0008]
parity_test: none
---

## Context
Largest chunk of the schema and the load-bearing core for E-07. Sacred-cow shapes: single Flight entity discriminated by `FlightAircraftType`, glider↔tow link via `TowFlightId`, cross-tenant crew references.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Translate `Flight` columns from S-010 baseline; reshape allowed but document deltas.
- [ ] Self-FK on `tow_flight_id`.
- [ ] FlightCrew M:N between Flight and Person; with `FlightCrewType` lookup.
- [ ] FlightType + FlightCostBalanceType with `is_for_glider`/`is_for_tow`/`is_for_motor` flags.
- [ ] Aircraft + AircraftType + AircraftState + AircraftOperatingCounter.
- [ ] Location + LocationType + InOutboundPoint.
- [ ] Article (per-club, used by accounting rules).
- [ ] Add the hot-path indexes (commit hash `99a69c4` in legacy got this right — port them).

## Notes
This story is L because it touches ~15 tables. Tasks split it into the verifiable sub-pieces. Don't try to do it all in one PR — land tables in groups (Flight + crew; Aircraft cluster; Location cluster) so review is tractable.
