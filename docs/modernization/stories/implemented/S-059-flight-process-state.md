---
id: S-059
title: FlightProcessState stored state + transition matrix
epic: E-07
status: in_progress
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
---

## Context
The other half of the 2D state machine. Where most of E-07's parity risk lives.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Translate `FlightService.cs:1380-1440` into a `FlightStateTransitions` map.
- [ ] Build the transition service.
- [ ] Cover every legal transition with a test.
- [ ] Cover key illegal transitions (DeliveryBooked → *; NotProcessed → Locked; etc.) with rejection tests.
- [ ] Integrate with audit log.

## Notes
L because the matrix must mirror legacy exactly. Legacy is ~14 transitions (operator-driven ~7 + system-driven ~7), not ~30 as previously stated.

ExcludedFromDeliveryProcess(99) is the side-branch — Valid/Locked/DeliveryPrepared/DeliveryPreparationError → Excluded → Valid. Preserve.

<!-- modernize-refine: start -->

## Design notes

- **State encoding — keep S-058's FK + add `FlightProcessState` Java enum.** Reference table (`flight_process_state`) stays; aggregate holds `UUID processStateId`. Enum carries the legacy numeric code (0/28/30/40/45/50/60/99) and resolves to/from UUID via a `FlightProcessStateLookup` bean mirroring `FlightInitialStateProvider`. Enum is the API + audit + matrix surface; UUID is the storage shape. Reusing S-058's pattern avoids a destructive migration for zero gain.
- **Matrix is an in-code `Map<FlightProcessState, Set<FlightProcessState>>`** in `flights.domain` (`FlightTransitionMatrix`). YAML adds a parser + resource lifecycle and no flexibility we will ever use. The matrix is reviewed in PR diffs like any other business rule.
- **Aggregate vs service split (per ADR 0018).** `Flight.transition(target, actor, clock)` enforces matrix + post-Locked immutability + same-state semantics; throws `IllegalFlightTransitionException`. `FlightStateTransitionService` (application) loads the aggregate, calls `transition`, emits `AuditTrail`, and orchestrates tow cascade. Aggregate owns invariants; side effects (audit, cascade) are application concerns.
- **Tow cascade is opt-in.** `Flight.transition` does not cascade. The service exposes `transitionWithTowCascade(gliderId, target, systemActor)` used only by system-driven paths (validator / bulk-lock / delivery-prep — all in deferred stories). Manual operator endpoint calls plain `transition` per flight — matches legacy intent that operators see exactly what they're changing. Cascade applies the same target to the paired tow flight in one transaction; mismatch rolls both back. Legacy refs: `FlightService.cs:1008-1014`, `DeliveryService.cs:191-196`.
- **Optimistic locking lands HERE.** S-058 shipped `Flight` without `@Version`; bulk transitions racing operator edits would silently last-writer-win and corrupt the audit trail. Add `@Version Long version` + `version BIGINT NOT NULL DEFAULT 0` column in this story's migration. JPA `OptimisticLockException` → HTTP 409. Conflict rate near zero in normal use; 1–2 application-level retries are sufficient — no load test needed.
- **Audit reuses existing `AuditTrail` + `AuditAction.STATE_TRANSITION`.** Payload `{flightId, fromState, toState, actor, trigger}` where `trigger` ∈ `OPERATOR | VALIDATOR | LOCK_JOB | DELIVERY_PREP | BOOKING`. Audit is the only durable history of state changes — Flight stores current state only.
- **Actor model.** Sealed `Actor` interface with `UserActor(UserId)` and `SystemActor` cases; `SystemActor` is a small enum (`VALIDATOR / LOCK_JOB / DELIVERY_PREP / BOOKING`). Audit serialises both uniformly. No fake user rows.
- **`IllegalFlightTransitionException`** in `flights.domain`; mapped to HTTP 409 by the existing `FlightsExceptionHandler` with body carrying `from / to / allowed[]`.
- **Schema unchanged from S-058 apart from `@Version`.** No CHECK constraint encoding the matrix (ADR 0022 directive 2). Schema keeps `process_state_id NOT NULL` + FK + `@TenantId` + new `version` column. Matrix is Java.
- **Scope fences.**
  - **Ships:** matrix, `Flight.transition`, `transitionWithTowCascade`, `IllegalFlightTransitionException`, `Actor`/`SystemActor`, audit emit, manual endpoint `PATCH /flights/{id}/process-state`, `@Version` on Flight.
  - **Defers to S-061:** time-gate predicates wrapping `transition`; force-lock authz.
  - **Defers to delivery story (S-064 / TBD):** invoking `Locked → DeliveryPrepared / DeliveryPreparationError / Excluded` and the delivery-row deletion side effect on `DeliveryPrepared → Locked / → Excluded`. S-059 declares these edges legal in the matrix; downstream wires the caller and owns deletion. Contract documented on `transition`: caller must delete deliveries before invoking the transition out of DeliveryPrepared.
  - **Defers to validator story (no S-NNN yet — flag for backlog):** `NotProcessed → Valid / Invalid` and `Invalid → Valid / Invalid`. Edges legal in matrix; validator not invoked here.
- **Downstream guidance.** Bulk callers iterate hundreds-to-low-thousands of flights per club per night — chunk + commit per batch (~100), don't open a single transaction across the full set. Per-flight audit emit is fine at this scale; no streaming audit needed.

## Edge cases & hidden requirements

- **Same-state transition** (e.g. `Valid → Valid`) — legacy rejects with `BadRequestException` (`FlightService.cs:1378-1444`). Recommend mirror: reject with `IllegalFlightTransitionException` → 409, emit no audit row. See Open design question #1.
- **Concurrent transitions.** Operator + bulk job racing on the same flight: `@Version` mitigation (above). On `OptimisticLockException`, application service retries once with reload; second collision returns 409 to caller.
- **Cross-tenant access** returns 404, not 403. `@TenantId` already filters `findById` to `Optional.empty`; transition service re-asserts `flight.tenantId().equals(currentTenantId())` as defense in depth. Existence is not disclosed across tenants.
- **Excluded → Valid does not re-run validation.** Legacy just stamps the state (`FlightService.cs:1432-1435`); a now-invalid flight stays Valid until the next validator pass flips it. Preserve. Document — do not silently "fix".
- **Bulk-job partial failure.** Legacy `LockFlights` / `ValidateFlights` swallow per-flight exceptions and continue (`FlightService.cs:1180-1183, 942-945`). The single-flight `transition()` throws; bulk wrappers (downstream stories) iterate-collect-log. Note in `transition`'s javadoc so downstream doesn't accidentally wrap an all-or-nothing transaction.
- **Same audit payload includes BOTH from and to.** `AuditAction.STATE_TRANSITION` semantics require capturing the prior state — otherwise the matrix is not reconstructable from audit alone.
- **Initial state on create stays with `FlightInitialStateProvider`** (S-058). The transition service must NOT introduce a parallel resolution path for the NotProcessed UUID; reuse the lookup.
- **Legacy bug to preserve / fix decision:** `DeleteDeliveriesAndUpdateProcessStatesOfFlight` at `FlightService.cs:1482` mutates `flight.ProcessStateId` instead of `towFlight.ProcessStateId` (typo). Cascade implementation in `transitionWithTowCascade` should do the right thing; do not port the bug.

## Security plan

- **Authz on `PATCH /flights/{id}/process-state`** — `@PreAuthorize` with a `@flightAccess.canTransition(#id, authentication.principal)` SpEL bean (mirrors `AircraftAccess`). Any tenant member with `FLIGHT_EDIT` permission; line pilots correct their own flights. Force-lock (`Valid → Locked` early-via-admin) is **S-061's** scope and gates on `CLUB_ADMINISTRATOR` per legacy `FlightService.cs:1145-1153` — do NOT collapse this endpoint into admin-only.
- **Tenant isolation** structurally enforced by `@TenantId`; service re-asserts equality and returns 404 on mismatch. Per S-159, no `SYSTEM_ADMINISTRATOR` side-channel.
- **Mandatory audit on every accepted transition** (operator AND system paths). Skipping emission on the system path is a forensic gap, not a perf optimization. Payload contains state codes + actor + trigger only — no PII; `AuditedTarget` redaction remains the policy for any future payload extension.
- **OWASP scope that genuinely applies:** A01 (covered by authz + tenant assertion) and A09 (covered by mandatory audit). Rest don't apply to a state-code endpoint.

## Test plan

1. **Domain unit (bulk)** — one `@ParameterizedTest` over the full `(from, to, trigger)` matrix: legal transitions accepted, every other pair throws `IllegalFlightTransitionException`. Trigger scoping covered (e.g. `Valid → Locked` rejected for `OPERATOR`, accepted for `LOCK_JOB`). Plus editorial cases: `DeliveryBooked` is a sink, same-state rejected, unknown enum value rejected.
2. **Application service (Mockito)** — one `STATE_TRANSITION` audit row per successful transition with `{flightId, from, to, actor, trigger}`; no audit + no save on rejected transition; exception propagation. Tow cascade: `transitionWithTowCascade` applies to glider AND linked tow in one transaction; mismatched tow state rolls both back.
3. **Web slice (`@WebMvcTest`)** — `IllegalFlightTransitionException` → 409 problem+json with `from/to/allowed[]`; `OptimisticLockException` → 409 "modified concurrently"; unknown/missing state → 400; not found / cross-tenant → 404; happy path → 200 with new state in body.
4. **Repository (`@DataJpaTest` + Testcontainers)** — state mutation flushes and reloads; `@Version` increments on legal transition; same-state attempt does not silently swallow (regression for legacy `FlightService.cs:1445-1452` save-skip bug).

**Parity:** the parameterized matrix table IS the oracle, hand-transcribed from `FlightService.cs:1375-1444` with row-level line citations. No legacy-replay harness — S-102 owns the e2e parity at `tests/flights/06-flights-state-transitions.spec.ts`.

**Out of scope:** time-gate boundaries (S-061), delivery-row deletion (S-064 / TBD), validator rule logic (TBD), Playwright workflow (S-102).

## Performance plan

Thin surface. Matrix lookup is O(1) in-memory; per-flight `transition()` is sub-millisecond domain logic + one UPDATE + one INSERT (audit), not a hot path. Index `flight(operating_club_id, process_state_id)` already exists from S-058 (`V3__flights_aircraft_locations.sql:446`); covers `findByProcessState`. The only structural perf-relevant change is `@Version` on Flight (decided under design notes); concurrency-conflict rate is expected near zero, bounded application-level retry (1–2 attempts) is sufficient.

## Open design questions

1. **Same-state semantics.** Design notes + requirements + legacy all converge on "reject same-state with 409, emit no audit". Security plan as drafted also said 409. **Recommended: reject with 409, no audit.** Operator confirmation needed only because the alternative (silent no-op for retry idempotency) was raised — and rejected here because it masks double-submit bugs.
2. **LockFlights time-gate semantic shift — `CreatedOn` vs `flight_date`.** Legacy bulk-lock filters on `flight.CreatedOn <= today - 2d` (`FlightService.cs:1164`); S-061 specifies `flight_date <= today - 2d`. A back-dated flight (flight_date last week, created today) would lock immediately under S-061, never under legacy. Belongs to S-061, but surfaced here because the matrix story is the natural place for operator review. **Action: confirm with operator whether S-061's `flight_date` semantics are intentional; if not, S-061 needs an AC fix.**
3. **Locked-edit gate scope.** Legacy `UpdateFlightDetails` blocks edits only when `ProcessStateId == DeliveryBooked` (`FlightService.cs:1276-1280`). Locked / DeliveryPrepared / DeliveryPreparationError flights remain editable in legacy — which looks like a bug (the lock + delivery-prep should freeze the flight, otherwise downstream books garbage). **Action: confirm intent.** If "Locked means locked" is the intended rule, this story (or a sibling) needs an AC to gate `UpdateFlightDetails` on `processState < Locked || == Excluded`. If legacy semantics are intentional, document.

<!-- modernize-refine: end -->
