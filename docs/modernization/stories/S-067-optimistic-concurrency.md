---
id: S-067
title: Flight concurrency + delete-from-list + aerotow round-trip
epic: E-07
status: in_progress
started_at: 2026-05-25
github_issue: 128
github_pr: 127
depends_on: [S-058, S-062c]
acceptance:
  - `flight.version` `@Version` column added (already present on `Flight.java:208-210` from S-058 — story confirms migration alignment + OpenAPI surfacing).
  - PUT endpoints accept `If-Match: <version>` header; 412 Precondition Failed on mismatch with a structured `ProblemDetail` body (`type=concurrency/conflict`, includes `serverVersion`).
  - DELETE endpoint accepts and honors `If-Match` symmetrically.
  - OpenAPI documents the `If-Match` header param + 412 response shape on PUT + DELETE so the generated TS client surfaces them.
  - SPA forms include the version in mutations (`FlightStore.updatePair` / `savePair`-link / `deleteOne` already plumb it — confirm wire-up).
  - Concurrency-race test (backend integration, real DB): two clients PUT the same flight; first wins, second gets 412 with `serverVersion` in body.
  - **Aerotow happy-case e2e spec** (`04c-flights-paired-create.spec.ts` — deferred from S-062c's AC10): walks the wizard with start type = Aerotow, fills every wizard-rendered field on both glider and tow, submits, asserts POST→POST→PUT-link order with `If-Match: <gliderVersion>` on the link PUT, then asserts the wizard-rendered subset round-trips byte-identical on reload via `GET /flights/{gliderId}` + the linked tow GET. Compensating-DELETE-on-tow-fail variant covered in the same file.
  - **Form-mapper round-trip unit spec** (`flight-form.model.spec.ts` extension): drives a full `FlightFormSnapshot` (every editable attribute populated — incl. fields not yet exposed in the wizard: coPilot/instructor/observer/passenger/winchOperator, ldgLocationId, outbound/inboundRoute, engine counters, FCB, invoice recipient, couponNumber, noStart/LdgTimeInformation, isSoloFlight) → `snapshotToCreateRequests` → simulated server echo → `flightDetailToFormSnapshot` → assert byte-identical to original on every field. Closes "are all attributes actually saved" for the surface the wizard doesn't render today.
  - **Delete-from-list flow**: kebab menu on `flights-list.page.ts` gains a Delete item; opens an `<af-dialog>` confirm; on confirm calls `FlightStore.deleteOne(id, version)` with `If-Match`. Delete item is disabled (or omitted) when `processState === DELIVERY_BOOKED`. Backend already gates non-deletable terminal states via `FlightStateGateException` — UI mirrors that gate as a hint, not a hard gate.
  - Playwright spec for the delete flow: row delete → confirm → row gone; cancellation closes the dialog without firing DELETE.
estimate: M
adr_refs: [0005]
parity_test: alpenflight/web/e2e/tests/flights/04c-flights-paired-create.spec.ts
refined: true
refined_at: 2026-05-25
refined_specialists: [synthesized-inline]
---

## Context

R14 callout: concurrent-edit behavior is untested in legacy. New system should handle it properly from the start — Flight is the highest-frequency editable entity.

S-062c shipped the `If-Match` plumbing on every PUT (`FlightStore.updatePair`, `FlightStore.savePair` link PUT, `FlightStore.deleteOne`) but the server `@Version` column doesn't exist yet, so the header is currently advisory. This story makes the column real and turns the existing plumbing into an end-to-end concurrency gate.

## Coverage gap S-062c left on the table

S-062c shipped two ported parity specs (`04-flights-create`, `05-flights-edit`) and only the self-start happy path. The wizard's **aerotow** happy path — the case that actually exercises the paired-create POST→POST→PUT-link orchestration + the If-Match header on the link PUT + every editable attribute round-tripping — has **no e2e coverage today**. The only AEROTOW-aware tests are `flight-form.model.spec.ts` unit assertions on the DTO mapper's output shape; they don't verify the server actually persisted the fields nor that the read-back round-trips them.

That coverage is in scope for this story because:

1. `@Version` lands here, making the If-Match header on the link PUT a real concurrency gate rather than advisory plumbing.
2. The full-attribute round-trip on aerotow is the natural smoke test for the paired-create + If-Match combination together.
3. Without it, a regression in any of the 25+ fields the mapper handles would only surface in production.

The spec file already has a reserved name (`04c-flights-paired-create.spec.ts`) and the orchestration code shipped at `alpenflight/web/src/app/features/flights/flight.store.ts:savePair` — the refine should derive the test plan against that code.

## Tasks

- [ ] Audit `@Version` migration alignment (column already on `Flight.java:208-210`; confirm Flyway migration + `nullable=false default 0`).
- [ ] Map `OptimisticLockException` → 412 `ProblemDetail` with `serverVersion` in the global `@ControllerAdvice` (PUT today returns 412 from the parse layer; confirm + extend to the lock-fail path).
- [ ] DELETE endpoint: accept `If-Match`, parse with the same `parseIfMatch` helper, pass `expectedVersion` to `softDeleteFlight`. Single-transaction guard so the tow-cascade either succeeds or the whole delete rolls back.
- [ ] OpenAPI: annotate PUT + DELETE with `@Parameter(If-Match)` + `@ApiResponse(412, ProblemDetail)`; regen the TS client; verify `FlightStore` calls still compile.
- [ ] **Backend concurrency-race IT** (`FlightsControllerConcurrencyIT.java`).
- [ ] **Mapper round-trip vitest** — extend `flight-form.model.spec.ts` with the full-snapshot identity test. Resolve `invoiceRecipientPersonId` + `isSoloFlight` round-trip per Open design questions.
- [ ] **`04c-flights-paired-create.spec.ts`** — aerotow happy-case + paired-create order + compensating-DELETE-on-tow-fail.
- [ ] **Flight delete UI**: kebab menu Delete item + `<af-dialog>` confirm in `flights-list.page.ts`; disable on `DELIVERY_BOOKED`.
- [ ] **`flights-list-delete.spec.ts`** — Playwright for the delete flow.
- [ ] **`isSoloFlight` UI invariant**: add solo checkbox to the Glider step; hide co-pilot selector when checked. Server-side: persist as-given, do not derive.
- [ ] **`invoiceRecipientPersonId` round-trip**: add to `FlightDetail` + detail mapper (add column if missing on `Flight` entity).
- [ ] **Screenshots in the new specs** (per `alpenflight/web/CLAUDE.md` §8): `04c-flights-paired-create.spec.ts` writes `screenshots/flights/04c-NN-<state>.png` at each asserted state (wizard launch, glider step filled, tow step filled, post-submit, tow-fail rollback). `flights-list-delete.spec.ts` writes `screenshots/flights/delete-NN-<state>.png` (kebab open, dialog open, post-delete). Backfill `04-flights-create.spec.ts` + `05-flights-edit.spec.ts` opportunistically since both are touched by the surface this story changes.
- [ ] **Boyscout — flights filter bar alignment** (verified visually from `alpenflight/misaligned-filter.png`):
  - `af-date-picker` host: add `class: 'block w-full'` + an inner override (`::ng-deep .ant-picker { width: 100% }` or a token bridge) so the input fills its grid column. Today the From / To inputs leave visible right-side daylight inside their cells.
  - Height parity between `nz-date-picker` and `nz-select` at `default` size — the picker renders ~32px while the select renders ~40px in the same row. Pin both to the same min-height (likely via `--ant-control-height-lg` token bridge or explicit class).
  - Move "Clear filters" out of its own grid column — it currently eats column 5 with `items-end`, leaving a wide empty band. Either inline-trail after the Aircraft-type filter or relocate next to the "New flight" header action.

## Notes

Apply this to other high-edit entities in their respective stories (Aircraft, Reservation, PlanningDay) if the operator wants — for now scoped to Flight.

<!-- modernize-refine: start -->

## Design notes

**Structural state already in place; this story is the contract + UI completion pass.**

- `Flight.java:208-210` already declares `@Version long version` (shipped in S-058). The Flyway migration that added the column is `V13__flight.sql` (verify before opening a redundant one). If the column exists, the only schema work is asserting `nullable=false default 0` — no new migration unless the audit shows drift.
- `FlightsController.update` already parses `If-Match` (`FlightsController.java:127, 133-155`), strips weak/strong ETag wrapping, accepts `*` as "no precondition". Service layer enforces via `OptimisticLockException`. Story work: ensure 412 mapping in the global `@ControllerAdvice` returns a `ProblemDetail` carrying `serverVersion` (so the SPA's S-062h inline diff can render without an extra GET) — that mapping is the only ambiguous bit today.
- **DELETE symmetry is new.** Controller `delete()` currently ignores `If-Match`; client already sends it. Wire `@RequestHeader("If-Match")` + parse + pass to `softDeleteFlight(id, expectedVersion)`. The service's `assertMutationAllowed` already blocks `DELIVERY_BOOKED`; concurrency check stacks on top.
- **OpenAPI surfacing.** Add `@Parameter(in=HEADER, name="If-Match")` + `@ApiResponse(412, schema=ProblemDetail)` annotations on PUT + DELETE. The generated TS client picks up the header param as a typed argument — `FlightStore` is already calling with `{ headers: { 'If-Match': … } }` via the request-options third-arg overload; no client change.
- **Delete-from-list UI** is small but cross-cuts the kebab menu. Add a third `<li role="none">` to the dropdown in `flights-list.page.ts:374-402` mirroring the Edit/Copy items, plus an `<af-dialog>` instance for confirm. Disable the menu item when `fl.processState === DELIVERY_BOOKED` — the UI mirrors backend gate `assertMutationAllowed`. The cascade (glider delete soft-deletes the linked tow) is server-owned; UI surfaces the action once.
- **Aerotow e2e — wizard-rendered surface only.** The wizard MVP (S-062c) renders ~10 of the 25+ form-model fields; expanding the UI is S-062h scope. The e2e drives what the wizard can drive; the mapper round-trip unit spec covers the rest. Splitting "UI round-trip" (e2e) from "mapper round-trip" (vitest) keeps both honest without blocking on S-062h.
- **Mapper round-trip unit spec is the answer to "are all attributes saved".** Drives a fully-populated `FlightFormSnapshot` through `snapshotToCreateRequests` → constructs a `FlightDetail` from the request body (server-echo simulation) → runs `flightDetailToFormSnapshot` → asserts pointwise equality with the input. Any field the mapper drops (e.g. `invoiceRecipientPersonId` is currently always `null` in `detailToCrewSnapshot:211` — the `FlightDetail` shape has no field for it; the value lands on `Flight` but never round-trips into the form) **fails the test loudly**. This is the test you actually want.

**Known mapper gap surfaced by the round-trip spec (likely):** `invoiceRecipientPersonId` is on the form + on `FlightCreateRequest` but `FlightDetail` doesn't carry it back. Either the server omits it from the detail DTO (closing this gap is in scope for parity), or the form should not hold it at create-time. The unit spec will fail until one path is picked — the implementer chooses based on what the legacy server actually persists.

**Scope creep from messages on the refine prompt:** delete UI was not in the original AC. It's a small enough boyscout (UI plumbing only; backend gate exists) to land here per [[feedback-boyscout-rule-over-clean-prs]]; if the kebab + dialog work balloons past ~50 LOC the implementer should peel it into a sibling story rather than carry it.

## Edge cases & hidden requirements

- **412 on `savePair`'s link-PUT.** The version used in the `If-Match` on the link PUT is the `glider.version` returned by the POST. The server returns `version=1` for a fresh insert (Hibernate `@Version` semantics); the link PUT bumps to 2. If two clients race the paired-create + link, one of the two link-PUTs gets 412 — and at that point the second client has already orphan-POSTed a tow row. The current `savePair` only handles tow-POST failure (compensating glider DELETE). It does NOT handle link-PUT-412 failure. Acceptable for this story (the race window is microseconds because both POSTs of the second client succeed before the first client's link PUT) — but call it out in code as a known gap; S-062h or a sibling resilience story owns the full compensating chain.
- **DELETE `If-Match: *` semantics.** `parseIfMatch` already treats `*` as "no precondition" per RFC 7232. Symmetric DELETE plumbing should preserve that — useful for sysadmin force-deletes that aren't gated on version.
- **DELETE on a row whose paired tow is `DELIVERY_BOOKED`.** Server cascade tries to soft-delete the tow too; the tow's `assertMutationAllowed` throws. Today: orphan glider deleted, orphan tow stays. Either (a) wrap the cascade in a single transaction with a guard, or (b) document the half-state. Pick (a) — small change in `softDeleteFlight`.
- **OpenAPI 412 spec must include `application/problem+json`** as the response content type so the generated TS error class is typed (and the SPA can pattern-match `err.type === 'concurrency/conflict'` instead of stringly-checking `err.status === 412`).
- **`isSoloFlight` is currently UI-only** (`flight-form.model.ts:417`) — the form pushes it on create + update. Verify the server persists it (probably via `Flight.setIsSoloFlight` or derived from crew). The mapper round-trip spec asserts the field round-trips; if it drops, the implementer fixes the server side.

## Security plan

(N/A — no new auth surface. Existing `@PreAuthorize` on PUT/DELETE/POST already gates by role; `@TenantId` JPA filter still applies. 412 leaks the server's current `version` integer, which is non-sensitive — version monotonicity is not a covert channel.)

## Test plan

**Three test files, three concerns:**

1. **`alpenflight/server/.../FlightsControllerConcurrencyIT.java`** (new integration test, real Postgres):
   - `put_returnsCurrentDetail_whenIfMatchMatches`
   - `put_returns412WithProblemDetail_whenIfMatchStale` — asserts body shape `{ type: 'concurrency/conflict', serverVersion: 2 }`
   - `delete_returns412_whenIfMatchStale`
   - `delete_returns204_andCascadesToTow_whenGliderHasTowFlightId`
   - `delete_returns409_whenProcessStateIsDeliveryBooked` (the `FlightStateGateException` path — distinct from 412)
   - Two-client race covered by interleaving two `MockMvc` calls around a single load.

2. **`alpenflight/web/src/.../flight-form.model.spec.ts`** (extend existing vitest):
   - `snapshotToCreateRequests → server echo → flightDetailToFormSnapshot is identity for every field` — single test, table-driven over the 25-field surface. **This is the load-bearing one for "all attributes saved".**
   - Glider + tow variant. Includes the synthetic-sync fields (`startLocationId`, `startTime`, `outboundRoute` on tow mirror glider — assert post-sync identity, not pre-sync).

3. **`alpenflight/web/e2e/tests/flights/04c-flights-paired-create.spec.ts`** (new Playwright):
   - `aerotow happy-path: wizard → POST glider → POST tow → PUT-link with If-Match → reload round-trips wizard fields`
   - `tow-POST failure → compensating DELETE on glider → form re-displays error toast`
   - Mocks `**/api/v1/flights` with stateful handlers (POST returns version=1 + assigned id; PUT increments version; GET returns merged echo). Asserts request ORDER via captured-calls array.

4. **`alpenflight/web/e2e/tests/flights/flights-list-delete.spec.ts`** (new Playwright):
   - `delete row via kebab → confirm → row removed + DELETE /flights/{id} with If-Match observed`
   - `delete cancel via dialog dismiss → no DELETE fired`
   - `DELIVERY_BOOKED row: kebab item is disabled or absent`

Vitest covers the field-level "saved" guarantee; Playwright covers the UI orchestration. No `*.component.spec.ts` per [[feedback-fe-tests-unit-for-logic-playwright-for-dom]].

## Performance plan

(N/A — single-row PUT/DELETE; no new indexes; @Version row-level optimistic locking has no measurable overhead at this fleet size.)

## Resolved on refine (operator decisions)

- **`invoiceRecipientPersonId` — keep it.** Don't drop from the form. The implementer adds the field to `FlightDetail` + the service detail-mapper so it round-trips. Verify whether the column already exists on `Flight` entity; add the column if missing (structural, ADR 0022 directive 2). The round-trip vitest spec drives this to green.
- **`isSoloFlight` — UI-validated, not server-derived.** The wizard owns the truth: an `isSoloFlight` checkbox on the Glider step, and the **co-pilot selector is hidden when `isSoloFlight === true`** (so the form can never push a contradictory state). On the server side: persist the field as-given; do NOT derive from crew. The mapper round-trip spec asserts identity; the wizard's reactive rule (already half-wired in `FlightFormCoordinator` — see S-062c "Masterdata list-projection enrichment" follow-up) enforces the UI invariant. Implementer task: wire the show/hide rule + add the checkbox to the Glider step.

<!-- modernize-refine: end -->

