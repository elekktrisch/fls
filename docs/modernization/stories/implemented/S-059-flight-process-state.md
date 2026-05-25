---
id: S-059
title: FlightProcessState stored state + transition matrix
epic: E-07
status: done
started_at: 2026-05-24
depends_on: [S-058]
acceptance:
  - `FlightProcessState` enum: NotProcessed(0), Invalid(28), Valid(30), Locked(40), DeliveryPreparationError(45), DeliveryPrepared(50), DeliveryBooked(60), ExcludedFromDeliveryProcess(99).
  - A `FlightStateTransitions` table-driven implementation enumerates legal transitions (port of `FlightService.cs:1380-1440`).
  - Transition method `transition(flight, newState, actor)`:
     - Validates the transition is legal (raises `IllegalFlightTransitionException` otherwise).
     - Validates time-gates (S-061 — coupled but landed in S-061's tasks).
     - Writes audit event.
     - Persists.
  - **DeliveryBooked is terminal** — any transition out of it rejected.
  - Unit tests cover all defined transitions (positive) and at least 10 illegal transitions (negative).
estimate: L
adr_refs: [0008, 0018, 0019, 0022, 0023]
parity_test: tests/flights/06-flights-state-transitions.spec.ts (legacy spec, expanded in S-102)
refined: true
refined_at: 2026-05-24
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer, performance-engineer]
github_issue: 114
github_pr: 116
merged: true
merged_at: 2026-05-25
---

## Context
2D state machine — the half S-058 deferred. Most of E-07's parity risk lives here.

## Load-bearing decisions
- **No schema-level matrix.** Per ADR 0022 directive 2, transitions are enforced in Java (`FlightTransitionMatrix`), not via DB CHECK. Audit row carries `{from, to, actor, trigger}`.
- **`@Version` added in this story.** S-058 shipped `Flight` without it; bulk transitions vs operator edits would silently last-writer-win. `OptimisticLockException` → 409 with bounded application-level retry (1–2 attempts).
- **Tow cascade is opt-in, not implicit.** `Flight.transition` doesn't cascade. Service method `transitionWithTowCascade` exists for system paths (validator / bulk-lock / delivery-prep — deferred). Manual operator endpoint calls plain `transition` per flight — matches legacy intent that operators see exactly what they're changing.
- **Trigger scoping is real.** `Valid → Locked` rejected for `OPERATOR`, accepted for `LOCK_JOB`. See `TransitionTrigger`.
- **DeliveryBooked is terminal.** Any transition out of it rejected at the matrix level.
- **Actor model.** Sealed `Actor` (`UserActor` + `SystemActor`); no fake user rows in audit.
- **Legacy bug NOT ported:** `DeleteDeliveriesAndUpdateProcessStatesOfFlight` (`FlightService.cs:1482`) mutates `flight.ProcessStateId` instead of `towFlight.ProcessStateId`. Cascade implementation does the right thing.

## Out of scope (filed)
- **S-061** — time-gate predicates wrapping `transition`; force-lock authz (`CLUB_ADMINISTRATOR`).
- **S-064 / TBD (delivery)** — invoking `Locked → DeliveryPrepared / DeliveryPreparationError / Excluded` and the delivery-row deletion side effect on `DeliveryPrepared → Locked / → Excluded`. S-059 declares these edges legal; downstream wires the caller and owns deletion.
- **Validator (no S-NNN yet — backlog)** — invoking `NotProcessed → Valid / Invalid` and `Invalid → Valid / Invalid`. Edges legal; validator not invoked here.
- **S-102** — e2e parity at `tests/flights/06-flights-state-transitions.spec.ts`.

## Boyscout (this PR)
- `.github/workflows/ci.yml` — removed the API-client auto-commit. GitHub's recursive-workflow protection suppresses `pull_request` events after any `GITHUB_TOKEN` push to the PR branch, leaving the `required` check permanently unsatisfiable on the next push. Reverted to fail-with-clear-message; developers regenerate via `pnpm run generate-api` locally and commit.
- `.claude/skills/handoff/` — added the handoff skill.

## Open questions carried
1. **LockFlights time-gate semantic shift** — legacy bulk-lock filters on `flight.CreatedOn <= today - 2d` (`FlightService.cs:1164`); S-061's spec uses `flight_date <= today - 2d`. A back-dated flight (flight_date last week, created today) would lock immediately under S-061, never under legacy. Surface to operator when refining S-061.
2. **Locked-edit gate scope** — legacy `UpdateFlightDetails` blocks edits only at `DeliveryBooked` (`FlightService.cs:1276-1280`); Locked / DeliveryPrepared / DeliveryPreparationError remain editable. Looks like a legacy bug. Needs operator confirmation; if "Locked means locked" is the intended rule, S-061 (or a sibling) needs an AC to gate `UpdateFlightDetails` on `processState < Locked || == Excluded`.
