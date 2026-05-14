---
id: E-07
title: Flight operations & state machine parity
status: todo
adr_refs: [0005, 0008]
---

## Goal
Port the single `Flight` entity (discriminated by `FlightAircraftType`), the two-dimensional state machine (`FlightAirState` computed + `FlightProcessState` stored), the time-gating semantics (≥2 days lock, ≥3 days delivery), validation, the glider↔tow link, flight reports, and the new OGN ingestion API endpoint (C8). Sacred-cow heavy — most of the parity risk outside the rules engine lives here.

## Scope
- In: Flight + FlightCrew + FlightCrewType + FlightType + StartType entities; create/edit/list endpoints; state transition matrix as code (port of `FlightService.cs:1380-1440`); time-gate enforcement; glider↔tow recursion in validation; air movements UI; flight reports + custom report builder; OGN ingestion REST endpoint; FlightStateMapper enum from generated OpenAPI client (closes R5).
- Out: scheduled flight-validation job and locking transitions automation (E-10); accounting downstream of flight state (E-09).

## Stories
- [ ] S-058 — Flight entity + FlightAircraftType discriminator
- [ ] S-059 — `FlightProcessState` stored state + transition matrix
- [ ] S-060 — `FlightAirState` computed state derivation
- [ ] S-061 — Time-gate enforcement (≥2d lock, ≥3d bill) — code + tests
- [ ] S-062 — Flight create/edit (glider + tow forms — single entity, dual UI)
- [ ] S-063 — Glider↔Tow link integrity (TowFlightId recursion in validation + cascade semantics)
- [ ] S-064 — Air movements (motor aircraft) UI + endpoint parity
- [ ] S-065 — Flight reports + custom report builder
- [ ] S-066 — OGN ingestion REST endpoint (replaces direct DB writes)
- [ ] S-067 — Optimistic-concurrency strategy on Flight (ETag / version column)

## Done when
- The transition matrix from `FlightService.cs:1380-1440` is mirrored in a `FlightStateTransitions` table-driven implementation; every defined transition has a positive and a negative test (illegal transition rejected).
- A flight created via the new POST OGN endpoint produces a Flight row indistinguishable from one written by the legacy OGNAnalyser direct DB write (same columns populated, same defaults).
- Specs `04` `05` `06` `07` `22` `33` pass; an *expanded* version of `06` and `22` (added in E-13) exercising illegal transitions also passes.
