---
id: S-060
title: FlightAirState computed state derivation
epic: E-07
status: todo
depends_on: [S-058]
acceptance:
  - `FlightAirState` enum: New(0), FlightPlanOpen(5), MightBeStarted(8), Started(10), MightBeLandedOrInAir(15), Landed(20), FlightPlanClosed(25).
  - `getCalculatedFlightAirState(Flight)` is a pure function — derives from timestamps + flags, **never stored**.
  - DTOs include the calculated air state in their JSON output (so the SPA gets it without computing it client-side).
  - Unit tests for each state derivation (8 cases minimum).
estimate: S
adr_refs: [0008]
parity_test: none
---

## Context
Sacred cow: FlightAirState is computed, never stored. Pure-function port from legacy `GetCalculatedFlightAirStateId()`.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Port the function from legacy `FlightService` / similar.
- [ ] Wire into DTO serialization (`@JsonInclude` getter or DTO mapper).
- [ ] Unit-test all 8 enum values.

## Notes
The SPA's legacy `FlightAirState` filter dropdown reads this value — so the wire-format must include it.
