---
id: S-060
title: FlightAirState computed state derivation
epic: E-07
status: done
started_at: 2026-05-25
done_at: 2026-05-25
depends_on: [S-058]
acceptance:
  - `FlightAirState` enum: New(0), FlightPlanOpen(5), MightBeStarted(8), Started(10), MightBeLandedOrInAir(15), Landed(20), FlightPlanClosed(25).
  - `getCalculatedFlightAirState(Flight)` is a pure function — derives from timestamps + flags, **never stored**.
  - DTOs include the calculated air state in their JSON output (so the SPA gets it without computing it client-side).
  - Unit tests for each state derivation (8 cases minimum).
estimate: S
adr_refs: [0008, 0022]
parity_test: none
refined: true
refined_at: 2026-05-25
refined_specialists: [requirements-engineer, solution-architect, qa-engineer]
github_issue: 118
github_pr: 119
---

## Context
Sacred cow: air-state is computed, never stored. Pure-function port from legacy `Flight.cs:175-206`.

## Load-bearing decisions

- **Drop `flight.air_state_id` + `flight_air_state` reference table.** S-058 carried the stored shape; sacred cow says never stored. V13 drops column + FK + the table. `FlightInitialState` loses its second seed lookup.
- **`FlightPlanOpen` kept as a reachable state.** Legacy needed it because flight plans could open with no timestamps. Replacement: nullable `flight.flight_plan_opened_on TIMESTAMPTZ`. Setting workflow deferred (no story yet — flag for backlog when the flight-plan-open UX is in scope).
- **`FLIGHT_PLAN_CLOSED` stays in the enum, never emitted by `airState()`.** Reachable only through process-state driven downstream operations (legacy compute also never returns it). Asserted by combinatorial sweep test.
- **Wire format: enum-name string.** Jackson default; Springdoc surfaces values cleanly; orval produces a string-literal union. No numeric dual emission. Response-only on `FlightDetail` + `FlightListItem`; create / update DTOs carry no air-state field at all (A04 mass-assignment defense).
- **Shared compute, two carriers.** `FlightAirState.compute(5 args)` is the single source of truth; `Flight.airState()` and `FlightRepository$ListRow.airState()` both delegate. Keeps list + detail view consistent without round-tripping through the entity for keyset pagination.

## Open questions carried

1. **Air-state list filtering scope.** Legacy `FlightFilterSettings.cs:10-14` lets the SPA filter by air-state. With `air_state_id` gone, filtering must translate to predicates over `(startDateTime, ldgDateTime, noStartTimeInformation, noLdgTimeInformation, flightPlanOpenedOn)`. Owner: a flight-list / overview story, not S-060.
