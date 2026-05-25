---
id: S-060
title: FlightAirState computed state derivation
epic: E-07
status: in_progress
started_at: 2026-05-25
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
---

## Context
Sacred cow: FlightAirState is computed, never stored. Pure-function port from legacy `Flight.cs:175-206`.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Drop `air_state_id` column + FK + `flight_air_state` seed table (Flyway migration).
- [ ] Drop `initialAirStateId` factory parameter from `Flight.createGlider/Tow/Motor`; remove `FlightInitialState`.
- [ ] Add `Flight.airState()` instance method (pure compute over timestamps + flags).
- [ ] Wire into `FlightResponse` via DTO mapper (response-only; never inbound).
- [ ] Parameterized domain unit test, table-driven, one row per reachable state + ordering traps; cite `Flight.cs:NNN` per row.
- [ ] One web-slice test asserts `"airState"` string in flight GET payload.

## Notes
The SPA reads `airState` for filter dropdowns; wire format is the enum name (`"LANDED"` etc.), not the legacy int.

<!-- modernize-refine: start -->

## Design notes

- **Compute lives on the aggregate** — `Flight.airState() -> FlightAirState` (per ADR 0022 directive 2). Legacy already had it as an instance method (`Flight.cs:175-206`); preserve the shape. A separate calculator service is overkill for a 6-line branch over the aggregate's own fields.
- **Drop the stored `air_state_id` column** that S-058 carried over (Flight.java + V11 seed). Sacred cow says never stored; carrying it just for the legacy `FlightPlanOpen` branch is the tail wagging the dog. S-060 ships V13: drop column + FK + `flight_air_state` seed table; remove the field, getter, and `initialAirStateId` factory parameter; delete `FlightInitialState`.
- **`FlightPlanOpen` branch:** legacy needed it because flight plans could open with no timestamps. Default: **add `flight_plan_opened_on Instant` nullable column**; `FlightPlanOpen` fires when `flightPlanOpenedOn != null && startDateTime == null && !noStartTimeInformation`. Same shape as `validated_on` / `delivery_created_on`. Setting it is a future flight-plan-workflow story; S-060 only adds the column + branch. See [[Open: drop FlightPlanOpen entirely]].
- **`FlightPlanClosed(25)` stays in the enum but is never returned** from `airState()`. Legacy compute also never returns it — it's reachable only via process-state ops downstream. Document + assert in tests.
- **Wire format: enum name string.** Jackson default; Springdoc surfaces values cleanly; ts-codegen produces a string-literal union. No numeric dual emission.
- **DTO surface:** getter on `FlightResponse` delegates to `flight.airState()` inside the existing mapper. Don't `@JsonProperty` the entity getter — keeps entity off the wire per CLAUDE.md "DTOs ≠ entities". Response-only.
- **No schema-level state machine** — no generated column, no trigger, no CHECK. Pure Java, consistent with how S-059 landed `FlightProcessState` (ADR 0022 directive 2).

## Edge cases & hidden requirements

- **Branch ordering is load-bearing.** Legacy evaluates `LdgDateTime` FIRST; a flight with both `ldgDateTime` set AND `noLdgTimeInformation=true` still resolves to `LANDED`. Same for `(startDateTime, noStartTimeInformation)` → `STARTED` wins. Tests cover the "flag set redundantly" combos.
- **Asymmetric edge:** `ldgDateTime` set but `startDateTime` null → `LANDED`. Aggregate's `assertTemporalOrdering` (Flight.java:439-441) is nullable-aware, allows this. Mirror legacy.
- **DTO breaking change.** `FlightDtos.java:47, 89` currently expose `airStateId UUID` (REQUIRED). Replace with `airState` (enum). OpenAPI snapshot regenerates accordingly. Always non-null (`NEW` is the floor); computed on each serialisation (no caching needed — 6 boolean checks).
- **AC #4 framing.** "8 cases minimum" should be reframed as "every reachable state + each redundant-flag combo + the `FlightPlanClosed` never-emitted assertion". Reachable states with FlightPlanOpen kept = 6 (NEW, FLIGHT_PLAN_OPEN, MIGHT_BE_STARTED, STARTED, MIGHT_BE_LANDED_OR_IN_AIR, LANDED). With FlightPlanOpen dropped = 5.
- **Tow flight's computed state on list views.** Legacy `GliderFlightOverview.cs:60` surfaces both glider + tow `airState`. Out of S-060 scope, but list/summary stories must recompute on the joined row.

## Security plan
(N/A — pure computation over already-tenant-scoped Flight; no new endpoints, no new authz surface)

## Test plan

- **Layering.** Domain unit test on `Flight.airState()` carries the bulk. One slim `@WebMvcTest` asserts the GET flight DTO payload contains `"airState"` as a string. No `@DataJpaTest`, no SpringBootTest, no Playwright.
- **Single `@ParameterizedTest`** over `(ldgDateTime, startDateTime, noLdgTimeInformation, noStartTimeInformation, flightPlanOpenedOn) -> expected`. The table IS the parity oracle, hand-derived from `Flight.cs:175-206` with row-level `// Flight.cs:NNN` citations.
- **Rows required:**
  - `LANDED` — `ldgDateTime` set (wins over every flag).
  - `MIGHT_BE_LANDED_OR_IN_AIR` — `noLdgTimeInformation=true` AND `startDateTime` set AND no `ldgDateTime`.
  - `STARTED` — `startDateTime` set, `noLdgTimeInformation=false`, no `ldgDateTime`.
  - `MIGHT_BE_STARTED` — `noStartTimeInformation=true`, no `startDateTime`, no `ldgDateTime`.
  - `FLIGHT_PLAN_OPEN` — `flightPlanOpenedOn` set, no other inputs.
  - `NEW` — all inputs null/false.
  - Redundant-flag combos: `ldgDateTime` + `noStartTimeInformation=true` still `LANDED`; `noLdgTimeInformation=true` without `startDateTime` falls through (not `MIGHT_BE_LANDED_OR_IN_AIR`).
- **`FLIGHT_PLAN_CLOSED` never-emitted assertion.** Single test asserts no input combination produces it; comment cites legacy `Flight.cs:175-206`.
- **Wire format.** Web-slice test: assert `"airState"` is the string enum name; one row per reachable value via `@MethodSource`.
- **Not tested here.** Air-state list filtering (see Open #2); downstream `GetCalculatedNrOfLandings` consumer; tow's computed state on overviews.

## Performance plan
(N/A — pure in-memory function, 6 boolean checks per serialisation; S estimate)

## Open design questions

1. **Keep `FlightPlanOpen` (adds a column) or drop it (smaller scope)?** Default in the design notes is keep + add `flight_plan_opened_on`. Drop is reasonable if the rewrite doesn't port flight-plan-open as a discrete workflow step — confirm with operator. Drop reduces enum to 6 reachable states + the never-returned `FLIGHT_PLAN_CLOSED`.
2. **Air-state list filtering scope.** Legacy `FlightFilterSettings.cs:10-14` lets the SPA request flights by air-state. Dropping `air_state_id` means filtering must translate to predicates on `(startDateTime, ldgDateTime, noStartTimeInformation, noLdgTimeInformation, flightPlanOpenedOn)`. **Confirm:** does S-060 own this translation, defer to a flight-list story, or drop the filter entirely?

<!-- modernize-refine: end -->
