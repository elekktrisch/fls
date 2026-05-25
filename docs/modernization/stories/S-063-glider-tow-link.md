---
id: S-063
title: Glider↔Tow link integrity (TowFlightId recursion in validation + cascade)
epic: E-07
status: in_progress
started_at: 2026-05-25
github_issue: 130
depends_on: [S-062a]
acceptance:
  - When a glider flight has `start_type=Towing`, a linked tow Flight is required (1:1 via `tow_flight_id`).
  - Validation of the glider flight recurses through `tow_flight_id` — both must be valid for the glider to reach Valid.
  - Updating one side of the pair (e.g. crew on the tow plane) preserves the link.
  - Cascade semantics on tow row when the glider is deleted: tow row is also deleted (or unlinked — confirm legacy behavior in legacy `FlightService.Delete`).
  - Depth tests cover: partial update on glider while tow is referenced; orphaned tow flights; tow flight without a glider.
estimate: M
adr_refs: [0008]
parity_test: tests/flights/05-flights-edit.spec.ts (smoke); depth in S-105
refined: true
refined_at: 2026-05-25
refined_specialists: [requirements, solution, qa]
---

## Context
Sacred-cow shape; legacy specs do not exercise it (R14 callout). Get the cascade wrong and orphan rows accumulate.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Validation recursion through the self-FK.
- [ ] Cascade definition: study legacy `FlightService.Delete` to confirm whether tow rows are deleted, unlinked, or preserved. Match.
- [ ] Tests covering the cascade + recursion.

## Notes
This is the kind of behavior that's easy to half-implement and not notice until a real-world flight produces an orphan. Be thorough on the tests; S-105 expands them further.

<!-- modernize-refine: start -->

## Design notes

**Cascade on delete — already correct.** `FlightsService.delete():237-260` (S-067) manually soft-deletes the tow when the glider is deleted; rolls back the whole transaction if the tow is admin-locked. Don't redesign — add a test that asserts the rollback path.

**Validation recursion — pick (b) AC-as-written.** Add `FlightPairValidator.validate(glider, FlightLookup)` as a sibling to `FlightValidator`. One-hop only: if `towFlightId != null`, load the tow and run `FlightValidator.validate(tow)`; if it returns any errors, append a single sentinel `VALIDATION_ERROR_Tow_flight_invalid` to the glider's result (no nested error list — keeps the wire shape flat). `FlightValidator` stays per-flight and pure. Rationale: legacy keeps the pair independently valid (`FlightService.cs:987-1015`), but the AC plus the daily-job port (S-083) want a single verdict per pair — coupling avoids "glider Valid, tow Invalid" pairs the operator must chase. **Parity divergence — surfaced in `## Open design questions` for operator sign-off.**

**Cycle safety.** One-hop by construction. `linkTow()` rejects caller-not-GLIDER and target-not-TOW, so a TOW row cannot have its own `towFlightId` set through the aggregate API. `Flight.towFlightId` is already package-private with no setter. No cycle guard inside the validator.

**Dangling FK to soft-deleted tow.** `FlightValidator` start-type arm at :89 must also check `tow.isDeleted()` after hydration. Emit `VALIDATION_ERROR_Tow_flight_missing_or_deleted` when the tow is missing or tombstoned. Currently undetected.

**Two-gliders-one-tow.** `linkTow()` does not prevent two gliders pointing at the same tow row — cascade then orphans the shared tow when the first glider is deleted. Add a pre-link check: `findByTowFlightId(tow.id)` must return empty or only `this`. Reject with `InvalidTowLinkException` otherwise.

**Partial-PUT drops link — fix as bug.** `FlightsService.updateFlight():160` reads `req.towFlightId() == null` as "explicit unlink." A client PATCH that omits the field silently breaks the pair (AC: "updating one side preserves the link"). Change `FlightUpdateRequest.towFlightId` to a sentinel that distinguishes "field absent" from "explicit null" (e.g. `JsonNullable<UUID>` or `Optional<>` with a custom deserializer). This is the natural follow-up to S-067's optimistic-concurrency contract.

**Re-link drops old tow → orphan.** Editing the glider's `towFlightId` to a different tow leaves the old tow row orphaned. Legacy does this too; keep it. Operational sweep (if ever needed) belongs in a separate hygiene story.

**Cross-story contracts:**
- Consumed by **S-077** (rules-engine glider→tow recursion) — wraps `FlightPairValidator`, no reimplementation.
- Consumed by **S-083** (DailyFlightValidationJob) — calls `FlightPairValidator` per glider; dedupes referenced tow rows at the job level.
- Consumed by **S-105** (depth tests).
- **S-161** (charter visibility) unaffected; `FlightLookup` must honor `@TenantId` so cross-club tow rows don't leak into a glider's validation.

**Schema enforcement: none.** ADR 0022 directive 2 holds — no triggers, no CHECKs, no generated columns. Self-FK with `ON DELETE NO ACTION` is the only structural rule.

## Edge cases & hidden requirements

- **Linked tow already soft-deleted (tombstoned).** Validator must hydrate and check `isDeleted()`; emit `VALIDATION_ERROR_Tow_flight_missing_or_deleted`. Currently undetected.
- **Tow in admin-locked / `DELIVERY_BOOKED` state when glider is deleted.** Cascade rolls back the entire delete (intentional, atomic outcome). Add explicit test — implementer must not "skip locked tow, delete glider anyway."
- **Charter / cross-club tow.** `linkTow` rejects different `operatingClubId`; legacy permits it implicitly. Keep the guard; document as known parity divergence. S-161 owns cross-club aerotow billing.
- **Orphan TOW rows.** Allowed steady state. No invariant. `findByTowFlightId(...)` already exposed for any future sweep.
- **Admin-set `EXCLUDED_FROM_DELIVERY_PROCESS` glider.** Cascade still applies (admin role passes `assertMutationAllowed`). Add a test.
- **Re-link to a different tow.** Old tow becomes orphan; document. No auto-cleanup.
- **Validator called on a TOW directly.** `startType=Aerotow` arm guards on `GLIDER` — no-op on tows. Confirm with a test; no guard needed.

## Security plan
(N/A — no auth / RBAC / PII / audit signal. Link integrity is a structural concern; `@TenantId` enforcement carries over from S-062a unchanged.)

## Test plan

**Pyramid.** Unit (validator + pair-validator + aggregate invariants) → integration (JPA cascade depth, partial update, orphan queries, charter rejection) → Playwright smoke only (depth in S-105) → parity oracle vs legacy.

**Domain unit tests** — new `FlightPairValidatorTest` locks in the AC-fork choice (glider receives sentinel when tow is invalid; no nested errors inherited). Extend `FlightDomainTest` with the two-gliders-one-tow rejection and the tombstoned-tow path. Extend `FlightValidatorTest` with the dangling-FK arm.

**Integration tests** — new `FlightTowLinkIntegrationTest` (Spring + Testcontainers):
- Cascade with tow in `DELIVERY_BOOKED` → whole delete rolls back; both rows survive.
- Cascade with admin-set `EXCLUDED_FROM_DELIVERY_PROCESS` glider → cascades through (admin role).
- Partial PUT on glider omitting `towFlightId` → link preserved (validates the sentinel fix).
- Re-link to a different tow → old tow row orphaned (allowed); new link valid.
- `findByTowFlightId(...)` respects `@TenantId` (charter-isolation smoke).
- Two gliders linking the same tow rejected.

**Playwright.** Smoke only at `e2e/tests/flights/05-flights-edit.spec.ts` — one happy-path edit on a paired flight asserting the link survives. Re-confirm the path during implement (S-067 may have shifted it). All depth defers to S-105.

**Parity oracle.** Drive legacy `DELETE /Flights/{id}` and validation against the seed via `next/ops/dev-up-full.sh`; replay against AlpenFlight; diff `tow_flight_id`, `process_state`, presence-of-row. Zero-delta gate on delete-outcome and on the validation arms in `FlightService.cs:987-1023`, EXCEPT the AC-fork divergence (glider validity coupled to tow) — captured as intentional delta.

**Fixtures.** No Java-side pair builder exists yet. Add `gliderTowPair(...)` to `alpenflight/server/src/test/java/.../support/` returning a persisted, linked pair under a test tenant.

## Performance plan
(N/A — no latency / index / cache / N+1 signal. One additional point read (`findByIdWithCrew(towId)`) during validation; not a hot path.)

## Open design questions

1. **Validation recursion — (a) strict parity vs (b) AC-as-written?** Resolved 2026-05-25: **(b) AC-as-written**. Implement via `FlightPairValidator` with sentinel `VALIDATION_ERROR_Tow_flight_invalid`. Parity divergence vs legacy `FlightService.cs:987-1015` documented in commit and PR description.

<!-- modernize-refine: end -->
