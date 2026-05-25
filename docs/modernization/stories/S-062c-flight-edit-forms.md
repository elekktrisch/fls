---
id: S-062c
title: Flight create/edit forms (glider + tow) + copy flow
epic: E-07
status: in_progress
started_at: 2026-05-25
github_issue: 124
depends_on: [S-062a, S-062b, S-007, S-008]
acceptance:
  - `/flights/new` renders a 3-step wizard shell (Launch → Glider → Tow) per the design-reference at `docs/modernization/design-reference/screenshots/flights-form.png`; Step 3 (tow) is skipped with empty state when `startType !== Towing` (parity with legacy `FlightsController.js:418-420, 666-673`).
  - `/flights/:id` loads an existing flight via `GET /flights/{id}` (S-062a) and patches the wizard; respects server-supplied `canUpdateRecord` / `canDeleteRecord` flags (whole wizard disables when `!canUpdateRecord`).
  - `/flights/copy/:id` fetches `GET /flights/{id}/copy-template` (S-062a), patches the wizard with the cleared draft, navigates to `/flights/new`'s save flow. Empty-UUID load normalization applied to BOTH edit-load AND copy-template.
  - Paired-create save flow orchestrates client-side per refined design (S-062a shipped flat single-row POST/PUT — no bundled endpoint): `POST /flights` glider → `POST /flights` tow → `PUT /flights/{gliderId}` with `If-Match: 1` body `{ towFlightId }`. Update is a single PUT per row. Tow-POST failure triggers compensating `DELETE /flights/{gliderId}` + i18n rollback toast; draft retained.
  - `FlightFormCoordinator` (plain TS, no Angular DI) implements all cross-field reactive rules from `## Client form mechanics`: start-location mirror glider→tow, start-time mirror, outbound-route mirror, solo-flight tri-state derivation, co-pilot clear on solo, invoice recipient clear when not required, aircraft change resets engine counters (conditional on `myClub.resetEngineOperatingCounters`), location change recomputes route requirement.
  - `prepareForSaving` mirror: glider→tow sync of `startDateTime`/`startLocationId`/`outboundRoute` BEFORE the tow-discard check; tow row data dropped when `!needsTowplane || !tow.aircraftId` (parity with `FlightsController.js:348-378`).
  - New `af-user-preferences-service` (Dexie-backed, OIDC `sub`-scoped) replaces raw localStorage: `lastStartLocation` auto-hydrated default; `lastTowAircraftId`, `towPilotByAircraftId`, `lastGliderOutbound/Inbound`, `lastTowOutbound/Inbound` via explicit "Copy from Last" buttons (button rendered only when source key present AND target field empty). Wiped on logout AND tenant-switch via shared session-lifecycle hook.
  - Smart defaults from `GET /flights/last-context` (S-062a) hydrate on cold new + aircraft picked, with resolution order: explicit Copy-from-Last > IndexedDB draft (S-062h) > `copy-template` > `last-context` > `new-template` > hardcoded fallback. Null `last-context` → silent fallback, no toast.
  - Parity specs `04-flights-create.spec.ts` and `05-flights-edit.spec.ts` green on the new stack with byte-identical behavior assertions (selector adaptation via `data-testid` allowed; behavior not).
  - New Playwright specs: `04b-flights-copy.spec.ts` (copy flow), `04c-flights-paired-create.spec.ts` (3-call orchestration + tow-fail rollback), `04d-keyboard-only.spec.ts` (first-pass happy path), `04e-mobile-wizard.spec.ts` (mobile reflow + touch-targets).
  - FlightStore extended with detail-state slice (`current`, `currentVersion`, `save`, `delete`, `loadDetail`, `loadNewTemplate`, `loadCopyTemplate`) emitting `MutationBus.flightChanged$` on save/delete.
  - Keyboard nav first pass (AC-DIR-3 scope split): Tab/Shift+Tab natural order, Enter advances step / submits on last, Esc cancel-with-dirty-confirm. Ctrl+D save+copy, number-key 1–5 flight-type quick-select, and AC-DIR-13 slide-in focus jump are deferred to **S-062i**.
  - On `412 Precondition Failed` (concurrent edit, full handler ships in S-062h): plumbs `If-Match` outbound on PUT and a non-blocking placeholder toast inbound. Inline diff dialog is S-062h's scope.
estimate: L
adr_refs: [0005, 0007, 0008]
parity_test: tests/flights/04-flights-create.spec.ts, tests/flights/05-flights-edit.spec.ts
refined: true
refined_at: 2026-05-25
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
split_from: S-062
---

## Context

Third of three sub-stories splitting the original S-062 (see [S-062a](S-062a-flight-crud-backend.md) and [S-062b](S-062b-flight-list-page.md)). The biggest UI piece. Two separate forms (glider, tow) sharing a coordinator and a shell, plus the copy flow. The reactive-form field rules are dense — see the Client form mechanics section.

Specs `04-flights-create.spec.ts` and `05-flights-edit.spec.ts` are the parity oracles; they don't go green until this story does. New e2e specs `04b` (copy) and `04c` (paired-create) are added here to cover behavior the legacy specs skip.

## Acceptance criteria
See frontmatter. **Several frontmatter AC clauses are superseded by the refined design and need rewriting at `/modernize-decompose` time** — they pre-date the wizard pattern, the orchestrated paired-create, and the Dexie-backed prefs primitive. The Refinement block below is authoritative until decompose folds the rewrite in.

## Tasks
- [ ] Replace placeholder routes from S-062b: `/flights/new`, `/flights/:id`, `/flights/copy/:id` now render the wizard shell.
- [ ] Shell page + 3 step components (Launch / Glider / Tow) per the wizard design notes; tow step skipped with empty-state when `startType !== Towing`.
- [ ] `flight-form.model.ts` — typed Reactive Forms model + `buildFlightForm()` factory (legacy-shaped `glider` / `tow` sub-FormGroups; flat-DTO mappers at the wire).
- [ ] `flight-form.coordinator.ts` — plain TS class, no Angular DI; hosts the ~30 `valueChanges` rules from Client form mechanics + submit-time `prepareForSaving` + 3-call paired-create orchestration.
- [ ] `flight-form.defaults.ts` — overlays `new-template` / `copy-template` / `last-context` with the prefs resolution chain.
- [ ] `af-user-preferences-service` — new Dexie-backed primitive (OIDC `sub`-scoped) consumed by Copy-from-Last AND recents wipe-on-logout. (Same Dexie store is later consumed by S-062h drafts.)
- [ ] Extend `FlightStore` (shared with S-062b) with detail-state slice + `MutationBus.flightChanged$` emit on save/delete. `If-Match` header plumbed on PUT + placeholder 412 toast handler (full diff dialog ships in S-062h).
- [ ] `<af-field-errors>` integration per S-007; stable `data-testid` (`flight-edit-<field>`) on every input + action.
- [ ] JIT-deferred S-008 primitives that land here: `<af-time-now-button>`, `<af-sticky-bar>`, `<af-dialog>` (consumed by Esc-dirty-confirm here; reused by S-062h restore + conflict prompts).
- [ ] `flight-form.coordinator.spec.ts` + `flight-form.defaults.spec.ts` + `flight-prefs.service.spec.ts` (Vitest). `conflict-resolver.spec.ts` moves to S-062h.
- [ ] Port parity specs `04-flights-create.spec.ts` + `05-flights-edit.spec.ts` (selector adaptation only).
- [ ] New Playwright specs (core): `04b-flights-copy.spec.ts`, `04c-flights-paired-create.spec.ts`, `04d-keyboard-only.spec.ts` (first-pass happy path), `04e-mobile-wizard.spec.ts`. (`04f` / `04g` / `04h` move to S-062h.)

## Notes

**Estimate calibration:** Bumped from M → L at decompose 2026-05-25 after the wizard + Dexie prefs primitive + paired-create orchestration were folded in. Drafts / conflict diff / marginal-3G split off into [S-062h](S-062h-flight-edit-resilience.md); keyboard polish (Ctrl+D / 1–5 / slide-in focus) split off into [S-062i](S-062i-flight-edit-keyboard-polish.md). S-062c retains: wizard shell + 3 steps + coordinator + paired-create + Dexie prefs (provisioned, drafts ship in S-062h) + Copy-from-Last + smart-defaults + first-pass keyboard + 6 Playwright specs (parity ×2, copy, paired-create, kbd-happy, mobile).

**Open risk — selectize replacement.** Legacy specs inject on `$scope` to bypass selectize (`04-flights-create.spec.ts:109-138`). New SPA uses ng-zorro `nz-select` which opens on `mousedown` not `click` — parity-helper rewrite, not pure selector-swap.

**Out of scope:**
- Motor-flight form — S-064 owns `/airmovements/*`.
- Validation rejection-path UX depth — S-101.
- Optimistic-concurrency `@Version` column at the DB — S-067 (this story plumbs `If-Match` + the 412 handler).
- Glider↔Tow cascade / orphan / concurrent edit depth — S-105.
- **Keyboard-nav polish (deferred):** Ctrl+D save+copy, number-key 1–5 flight-type quick-select, AC-DIR-13 slide-in focus jump. First-pass scope only here (Tab/Enter/Esc + `04d` happy path). File a follow-up story at decompose time.
- **Prototype-only fields (dropped):** `releaseAlt` (Step 1) and `fuel start/end/burn` (Step 3) — no legacy DTO mapping. Wizard renders without them. File a backend story if business confirms the need.

<!-- modernize-refine: start -->

## Design notes

### Visual reference + wizard shape

Canonical visual oracle: `docs/modernization/design-reference/screenshots/flights-form.png` per ADR 0024. **3-step wizard supersedes the original side-by-side ACs**: Step 1 Launch (`flightDate`, airfield = `glider.startLocationId`, launch method = `startType`), Step 2 Glider (aircraft, flight type, PIC + one-of co-pilot/instructor/observer/passenger by rule, times, landings, engine counters, winch op, comment), Step 3 Tow plane (aerotow only — skipped with empty-state otherwise). Single-column wizard at all viewports — **dense-desktop multi-column variant dropped** per operator 2026-05-25; vision §C22 / §F3 superseded. Persistent summary header above the stepper (stacks on `<lg`); page-header actions Cancel / Save draft / Submit (reflow into sticky footer save bar on `<lg`); footer Back · {n}/{N} · Next-or-Submit; stepper items jump-to-step. `releaseAlt` (Step 1) + `fuel start/end/burn` (Step 3) **dropped** — no legacy mapping; field-by-field table unchanged.

### Form model + flat-DTO mapping

Keep legacy-shaped `glider` / `tow` sub-FormGroups — the ~30 rules key off them, conditional render is cleaner. Mapper layer flattens at the wire: one form → two `FlightDetail` rows. Form does not carry `flightAircraftType`; mapper picks the enum. **Keep four separate FormControls** (`coPilot` / `instructor` / `observer` / `passenger`); flight-type rules drive which ONE is visible + the displayed label. Empty-UUID normalization (`00000000-…` → `null`) runs in the load-mapper for BOTH edit-load AND copy-template (regression risk if applied only to edit). `version` lives on the store slice (`currentVersion`), round-tripped via `If-Match` — never in the form.

### Paired-create orchestration

S-062a shipped flat single-row POST/PUT. Client orchestrates: (1) `POST /flights` glider → capture `gliderId`; (2) if aerotow + `tow.aircraftId`: `POST /flights` tow → capture `towId`; (3) `PUT /flights/{gliderId}` `If-Match: 1`, body `{ towFlightId: towId }`. **Tow-POST failure → compensating `DELETE /flights/{gliderId}`** + i18n "rolled back" toast; draft retained. Step 3 fail → pair unlinked, retry step 3 only. Future server-side `POST /flights/paired` is the deliberate seam.

### FlightFormCoordinator

Plain TS class, no Angular DI. Component calls `coordinator.attach(form, masterdataSignals, destroyRef)` once. Owns: all `valueChanges` rules (Client form mechanics is the verbatim oracle), flat-DTO mappers, submit-time `prepareForSaving` parity transforms (`FlightsController.js:370-372`), the 3-call paired-create chain.

### Storage primitives

Build **`af-user-preferences-service`** in this story — Dexie-backed, OIDC `sub`-scoped, single source for: Copy-from-Last keys (`lastTowAircraftId`, `towPilotByAircraftId`, `lastGliderOutbound/Inbound`, `lastTowOutbound/Inbound`, `lastStartLocation`), IndexedDB drafts (separate Dexie store `flight-drafts` from the SW mutation queue per ADR 0015), and the recents wipe-on-logout shared with `RecentlyUsedService`. **Zero raw localStorage writes.** `/flights/last-context` response is transient seed — refetched per form-open, never persisted to Dexie. Endpoint is workstation-scoped (no caller-`personId` filter) — legacy parity.

### Conflict UX

**412** (`If-Match` mismatch, stale version, the common concurrent edit) → `<af-dialog>` inline diff prompt (`flight-conflict-prompt`); first conflicting field focused; Enter activates keep-mine / keep-theirs; **no auto-retry**. **409** (DELIVERY_BOOKED state-gate or `ObjectOptimisticLockingFailureException` race) → non-blocking toast with "Reload latest"; no diff because state-gate is policy not data. `/modernize-decompose` should rewrite AC-DIR-12 and AC9 wording to match.

### Keyboard nav (first pass only)

In scope: Tab/Shift+Tab natural order across the wizard, Enter advances step / submits on last, Esc cancel-with-dirty-confirm (reuses the same `<af-dialog>` as draft-restore), `04d-keyboard-only.spec.ts` happy path. **Deferred:** Ctrl+D save+copy, number-key 1–5 flight-type quick-select, AC-DIR-13 slide-in focus jump.

### Story boundaries

**Inputs:** S-062a (endpoints + flat `FlightDetail` + `last-context` + `If-Match`); S-062b (`FlightStore` list slice + `MutationBus.flightChanged$`); S-007 (typed forms + `<af-field-errors>`); S-008 (ng-zorro primitives + `RecentlyUsedService` + `DensityService` + `ViewportService`; JIT-deferred here: `<af-time-now-button>`, `<af-sticky-bar>`, `<af-dialog>`); S-006 (Signal Store masterdata caches). **Outputs:** S-063 (paired-create contract precedent); S-064 (motor variant mirrors wizard pattern); S-067 (extends 412 coverage when `@Version` lands); S-101 (validation-rejection UX); S-102 (state-transition buttons); S-105 (cascade depth); S-110 (T3 navigation). **No schema-level business logic** introduced — ADR 0022 directive 2 holds.

### Recommended split for decompose

Carve AC-DIR-9 (IndexedDB draft + restore prompt), AC-DIR-12 (412 inline conflict dialog), AC-DIR-14 (marginal-3G + SW-queue UX) and specs `04f` / `04g` / `04h` into sibling **S-062c-resilience**. S-062c keeps the parity slice + paired-create + wizard + Copy-from-Last + smart-defaults + first-pass keyboard. Resilience lands on top once the happy path is green.

## Edge cases & hidden requirements

### Reality reconciliation (load-bearing)

- **AC3 / AC4 dead.** S-062a shipped flat single-row POST/PUT — paired-create is client-orchestrated (POST glider → POST tow → PUT link). Spec `04c` asserts the 3-call sequence + compensating DELETE on tow-fail.
- **412 vs 409 (settled).** 412 → inline diff; 409 → toast. AC-DIR-12 + AC9 wording is for decompose to rewrite.
- **Cross-tenant / empty-UUID FK shape.** Cross-tenant aircraft / flightType / location FK surfaces as 400 `DataIntegrityViolationException` with no `errors[].path` (S-062a "Out of scope NOT deferred"). Load-time empty-UUID → `null` normalization on both edit-load AND copy-template is load-bearing — without it the form silently 400s on save.
- **Wizard supersedes side-by-side.** Operator confirmed (design-reference). AC1/AC2/AC4 rewrite is decompose's call.
- **Persistence (settled).** Dexie via `af-user-preferences-service`; no localStorage carve-out.
- **2nd-seat slot (settled).** Four separate FormControls, one visible at a time by flight-type rule.
- **`last-context` (settled).** Workstation-scoped, legacy parity.

### Edge cases worth flagging (per AC-DIR)

- **AC-DIR-1** — wizard step jump mid-edit must preserve FormGroup state (no rebuild); viewport resize preserves draft + active step + scroll. (AC-DIR-2 dropped — no dense variant to switch between.)
- **AC-DIR-3 (first pass)** — Esc dirty-confirm reuses the draft-restore `<af-dialog>`; Enter on a step's last control advances, never submits before final step.
- **AC-DIR-4** — "Copy from last" button only rendered when source key exists AND target field empty (`flight-edit-tow-form.html:36-38` pattern). No dead buttons.
- **AC-DIR-5** — resolution order: explicit Copy-from-Last click > IndexedDB draft > `copy-template` > `last-context` > `new-template` > hardcoded fallback. Null `last-context` → silent fallback.
- **AC-DIR-6** — empty Recently-used bucket omits the group header entirely.
- **AC-DIR-7** — 400 (cross-tenant FK / empty-UUID) lacks `path` — map to form-level `flight.error.invalidReference` i18n toast with hint to the most likely FK control; never echo the offending UUID.
- **AC-DIR-8** — `<input type="date">` returns `yyyy-MM-dd`; keep `flightDate` as ISO-date string end-to-end (never `new Date(yyyy-MM-dd)` — local-TZ midnight drift). Times stay `HH:mm` string.
- **AC-DIR-9** — aircraft-change rebuild writes rebuilt values BEFORE the 500 ms draft debounce fires; draft holds rebuilt state. Route-key change is a distinct draft scope — never cross-restores.
- **AC-DIR-12** — focus first conflicting field on dialog open; keep-mine/theirs Enter-activatable; diff never serialized to telemetry.
- **AC-DIR-14** — second tab online-save first → first tab's queued PUT arrives stale → 412 → diff against user's own later edit. On form open, surface "queued save from prior session" indicator if SW queue has a pending mutation for this flightId.
- **Orphan person on copy-template** — server accepts (PersonClub gap); dropdown can't display the orphan ID. Show `<unknown person>` placeholder + inline "replace?" hint. Hard fix → S-101.

### Hidden requirements not in ACs

- **Submit-time glider→tow sync** of `startDateTime` / `startLocationId` / `outboundRoute` happens BEFORE the tow-discard check; tow row receives the synced values even though the UI mirrors were always disabled. Coordinator owns it; server is defense in depth.
- **Engine-counter reset on aircraft change is conditional** on `myClub.resetEngineOperatingCounters`. When false, counters preserved. Draft-restore wins over the reset rule on cold reload.
- **`SoloFlightCheckboxEnablementCalculator` is tri-state** (CHECKED-disabled / UNCHECKED-disabled / toggleable); ng-zorro checkbox is binary — needs `<af-toggle>` with a "locked" visual + tooltip, not a plain disabled checkbox.
- **`noStartTimeInformation` propagates glider→tow; `noLdgTimeInformation` does NOT.** Asymmetric by legacy design.
- **`nrOfLdgs` auto-defaults to 1** on first `ldgTime` blur (glider) / first `formatTowLanding` (tow) if unset.
- **Wizard step-jump must flush in-flight `valueChanges`** before nav, else the last keystroke is dropped between debounce and route change.
- **Mass-assignment defense** — submit DTO excludes `processState`, `operatingClubId`, `ownerId`, `validationErrors`, `version`, audit columns. Server rejects them anyway; stripping client-side avoids round-tripping a stale `version` from the form.
- **Session-lifecycle wipe** — Dexie store (drafts + recents + prefs) drains on logout AND tenant-switch via a shared hook. Shared-workstation PII boundary; not surfaced in the AC list.

## Security plan

### Threat model (form-page-specific)

- **PII echo in Dexie store on shared workstation** (med — drafts + recents + prefs carry person IDs, comments, coupons; origin-scoped not user-scoped). Mitigation: session-lifecycle hook wipes the Dexie store on logout AND tenant-switch (one hook, drains all three slices).
- **Conflict-prompt diff leakage via console / telemetry** (low — diff contains pilot names + comments + coupons). Mitigation: sanitize at source — telemetry emits `{flightId, fieldPaths[]}` only; never serialize `theirs` / `yours` payloads.
- **SW queue replay with expired bearer** (med, ADR 0015) — queued mutation drains post-reconnect with a stale token → 401. Mitigation: surface re-auth prompt with "keep / discard queued change"; never silently drop. Both the SW queue (origin-scoped) and the Dexie draft must survive re-auth.
- **412 retry-storm** (low). No auto-retry; inline diff requires explicit user action before next PUT.
- **`last-context` cross-caller PII** (low — workstation-scoped by design, legacy parity). Response treated as untrusted seed; never persisted to Dexie prefs slice; refetched per form-open.

### Authorization

Inherits S-062a. UI hides/disables save/delete + wizard body when `canUpdateRecord` / `canDeleteRecord` are false; server is the gate, client flag is UX-only.

### Input validation

Client validators are eager UX preview only; server `FlightValidator` (S-062a) is authoritative. Known S-062a gaps (NOT deferred here): (1) cross-tenant aircraft / flightType / location FK → 400 without path; (2) PersonClub membership for crew person FKs not validated at write. Both map to `flight.error.invalidReference` i18n toast with a generic hint; never echo the offending UUID.

### PII handling

Never log control values; toasts use i18n keys + field-path (`glider.pilotPersonId`), never the value. SW queue logs carry `flightId` + reason only. Conflict-prompt diff renders via Angular interpolation only; never serialized to `console.*` / telemetry.

## Test plan

### Coverage contract

Owns happy-path parity for create/edit/copy + the AC-DIR-* directives at the form layer. Backend coverage = S-062a; list coverage = S-062b; depth → S-101 / S-102 / S-105.

### Test pyramid

- **Vitest:** 4 logic-class specs (~12 cases total).
- **Playwright:** 9 specs (2 ported parity + 7 directive), grouped `S-062c-core` (6) and `resilience-split` (3).
- **Component (Angular Testing Library):** 0 — forbidden per memory `feedback-fe-tests-unit-for-logic-playwright-for-dom`.

### Vitest scope (logic classes only)

- `flight-form.coordinator.spec.ts` — ~30 cross-field rules exercised against a detached `FormGroup`: solo-derivation tri-state, co-pilot clear, invoice-recipient clear, glider→tow mirror, aircraft change engine-counter reset, `noStartTimeInformation` propagation, paired-create `toDto` flat-DTO split, `prepareForSaving` tow-discard.
- `flight-form.defaults.spec.ts` — resolution order precedence; empty-Guid normalization on load; copy clears timestamps/comment/coupon/counters but preserves aircraft + pilot.
- `flight-prefs.service.spec.ts` — Dexie-backed service keyed by OIDC `sub`: namespacing across `sub`, recency ordering, logout-wipe of `flight-drafts` + recents + prefs.
- `conflict-resolver.spec.ts` — 412 diff returns field-path list; per-field keep-mine/theirs merge; never serializes either side for telemetry.

### Playwright scope

**S-062c-core:**

| Spec | Scope |
|---|---|
| `04-flights-create.spec.ts` (ported) | Parity oracle for create — selector adaptation only. |
| `05-flights-edit.spec.ts` (ported) | Parity oracle for edit — selector adaptation only. |
| `04b-flights-copy.spec.ts` | Copy flow: source unchanged, new row distinct, cleared timestamps / comment / coupon / counters. |
| `04c-flights-paired-create.spec.ts` | Wizard 3 steps → Submit both → assert POST/POST/PUT order + `towFlightId`; force tow-POST 500 → compensating DELETE + i18n rollback toast + draft retained. |
| `04d-keyboard-only.spec.ts` | First-pass happy path: Tab order, Enter advances/submits, Esc dirty-confirm; zero `page.mouse.click`. Consumed by S-110-t3-smoke AC-DIR-2. |
| `04e-mobile-wizard.spec.ts` | `project: 'mobile'`: summary stacks, stepper vertical, sticky save bar, touch-target ≥44 px (AC-DIR-1/10). |

**resilience-split** (likely moves to sibling story per decompose):

| Spec | Scope |
|---|---|
| `04f-draft-restore.spec.ts` | Type → reload mid-wizard → restore prompt → values + active step recovered; route-key isolation between `new` / `edit:<id>` / `copy:<id>` (AC-DIR-9). |
| `04g-conflict-prompt.spec.ts` | Stub 412 → inline diff dialog, per-field keep-mine/theirs, Enter-activatable, no auto-retry; stub 409 state-gate → toast w/ Reload action. |
| `04h-marginal-3g.spec.ts` | `page.route` 200 ms RTT + intermittent loss: form interactive, save queues via SW, no spinner > 3 s; time-to-log stopwatch present, not value-gated (AC-DIR-11/14). |

### Parity gate

Zero-delta on `04-flights-create` + `05-flights-edit`. Only allowed change: `data-testid` / selector adaptation to ng-zorro markup (incl. `nz-select` `mousedown`-open helper). Field labels, validation copy, save outcomes, list-after-save state, redirect target — byte-identical to the legacy oracle.

### Risks

- `nz-select` opens on `mousedown` not `click` — parity-helper rewrite, not selector swap. Budget time in the port.
- Coordinator `patchValue` without `emitEvent: false` deadlocks in Vitest fakeAsync — explicit unit test, but easy to regress.
- IndexedDB / SW draft state leaks across Playwright workers — per-test browser context + `storageState: undefined` on every resilience-split spec.
- 412 / 409 stubs race the optimistic save — deterministic `page.route` delay only, no real-clock waits.
- Wizard step-jump mid-typing may swallow the in-flight keystroke if `valueChanges` lags route nav — coordinator flushes before step change; assert in `04c`.

## Performance plan

### Budgets

- Form-open cold: p95 < 3 s Fast 3G (flight GET + 5 master-data GETs all in parallel, never serialized).
- Form-open warm: p95 < 300 ms (master-data hits Signal Store cache per S-006; only flight GET / template GET round-trips).
- Save single: p95 < 500 ms (one POST/PUT).
- Save paired-create: p95 < 1500 ms (3 sequential round-trips; revisit when server-side `POST /flights/paired` lands).
- Draft auto-save: < 50 ms per write (500 ms debounce, single IDBTransaction, well under one frame).

### Fan-out shape

Master-data GETs (aircraft / persons / locations / flight-types / routes) issued in parallel with the flight GET on route entry — never chained behind it.

### Marginal-3G (AC-DIR-14)

Dropdowns from Signal Store cache; SW queues the save mutation per ADR 0015; UI surfaces "queued for sync" immediately so no spinner exceeds 3 s. Verified by `04h-marginal-3g.spec.ts`.

### Route-split

Code-split `flight-edit` at the route level — load only on `/flights/new`, `/flights/:id`, `/flights/copy/:id`. Coordinator + defaults budget ≤ 10 KB minzipped; the 3 wizard step components share the route chunk.

<!-- modernize-refine: end -->

## Client form mechanics

The detailed legacy field set, default chain, and cross-field reactive rules below are preserved verbatim as the parity oracle. The 3-step wizard above is the production layout; this section is the field-level contract those steps render against. (Held outside the refinement delimiters so re-refines don't risk dropping it.)

### Form structure

```ts
// Legacy DTO shape (preserved in modern form):
//   FlightDetails { FlightId, FlightDate, StartType, CanUpdateRecord, CanDeleteRecord,
//                   GliderFlightDetailsData, TowFlightDetailsData (nullable when !needsTowplane) }
// Crew fields are flat scalars on each *DetailsData block, NOT a nested crew array
// (FlightsController.js:425-427, 516, 529, 543, 557; flight-edit-glider-form.html:73,141,163,190,209,228).
// So the typed form mirrors that shape — *not* a nested per-crew-member group.

type FlightForm = FormGroup<{
  flightId:  FormControl<string | null>;          // hidden; null on create
  flightDate: FormControl<string | null>;         // ISO yyyy-MM-dd; native <input type="date">
  startType:  FormControl<number | null>;         // top-level (FlightsController.js:198, 666-673)

  canUpdateRecord: FormControl<boolean>;          // server-supplied permission (FlightService.cs:1741-1770)
  canDeleteRecord: FormControl<boolean>;

  glider: FormGroup<GliderFlightForm>;            // always present
  tow:    FormGroup<TowFlightForm>;               // present iff startType === Towing (FlightsController.js:418-420, 666-673)
}>;

type GliderFlightForm = {
  aircraftId:        FormControl<string | null>;
  flightTypeId:      FormControl<string | null>;
  pilotPersonId:     FormControl<string | null>;
  coPilotPersonId:   FormControl<string | null>;
  instructorPersonId:FormControl<string | null>;
  observerPersonId:  FormControl<string | null>;
  passengerPersonId: FormControl<string | null>;
  winchOperatorPersonId: FormControl<string | null>;

  startLocationId:   FormControl<string | null>;
  ldgLocationId:     FormControl<string | null>;
  outboundRoute:     FormControl<string | null>;
  inboundRoute:      FormControl<string | null>;

  startTime:         FormControl<string | null>;  // HH:mm
  ldgTime:           FormControl<string | null>;
  duration:          FormControl<string | null>;  // derived; also editable (FlightsController.js:738-743)
  noStartTimeInformation: FormControl<boolean>;
  noLdgTimeInformation:   FormControl<boolean>;

  nrOfLdgs:          FormControl<number | null>;
  engineStartOperatingCounterInSeconds: FormControl<number | null>;
  engineEndOperatingCounterInSeconds:   FormControl<number | null>;
  engineDurationSeconds: FormControl<number | null>; // computed mirror

  flightCostBalanceType:     FormControl<number | null>;
  invoiceRecipientPersonId:  FormControl<string | null>;
  couponNumber:              FormControl<string | null>;
  flightComment:             FormControl<string | null>;

  isSoloFlight:      FormControl<boolean>;        // auto-derived (FlightsServices.js:75-98)
};

type TowFlightForm = {
  aircraftId:        FormControl<string | null>;
  pilotPersonId:     FormControl<string | null>;
  flightTypeId:      FormControl<string | null>;

  // startLocationId / startDateTime / outboundRoute are MIRRORS of glider's values
  // (FlightsController.js:370-372). Modeled as disabled controls bound to a computed signal.
  startLocationId:   FormControl<string | null>;
  startTime:         FormControl<string | null>;
  outboundRoute:     FormControl<string | null>;

  ldgLocationId:     FormControl<string | null>;
  ldgTime:           FormControl<string | null>;
  duration:          FormControl<string | null>;
  noLdgTimeInformation: FormControl<boolean>;

  nrOfLdgs:          FormControl<number | null>;
  inboundRoute:      FormControl<string | null>;
  flightComment:     FormControl<string | null>;
};
```

**Notes:**
- No per-crew-member nested FormGroup in legacy. If new server API normalizes to a `crew[]` collection (per S-058), do that mapping in the API client, **not** in the form.
- `version` for optimistic concurrency lives in the FlightStore alongside the form, not on the form itself (S-067).

### Field-by-field rules

| Field | Required when | Visible when | Disabled when | Default | Notes / legacy cite |
|---|---|---|---|---|---|
| `flightDate` | always (HTML `required`) | always | `!canUpdateRecord` | new: `today` if no StartDateTime; copy: `res.FlightDate` | `flight-edit-form.html:17-20`; `FlightService.cs:1075-1076` |
| `startType` | always (server `:1096-1097`) | always | `!canUpdateRecord` | `flightDetails.StartType` ‖ `myClub.DefaultStartType` ‖ `"1"` | `FlightsController.js:198, 666-673` |
| `glider.flightTypeId` | server (`:1099-1100`); no client `ng-required` | always | `!canUpdateRecord` | `myClub.DefaultGliderFlightTypeId` | `flight-edit-glider-form.html:87-98`; `FlightsController.js:202` |
| `glider.aircraftId` | client (HTML `required`); server (`:1078-1079`) | always | `!canUpdateRecord` | none | `flight-edit-glider-form.html:17-30` |
| `glider.pilotPersonId` | server (`:1081-1082`); no client `required` | always | `!canUpdateRecord` | none | `flight-edit-glider-form.html:63-73` |
| `glider.coPilotPersonId` | never | `!isSoloFlight && !flightType.IsPassengerFlight && !flightType.InstructorRequired` | `!canUpdateRecord` | none; **auto-cleared** when `isSoloFlight==true` | `flight-edit-glider-form.html:173-191`; `FlightsController.js:425-427` |
| `glider.instructorPersonId` | `flightType.InstructorRequired` (visibility implies it) | `flightType.InstructorRequired` | `!canUpdateRecord` | none | `flight-edit-glider-form.html:192-210` |
| `glider.observerPersonId` | `ng-required="flightType.ObserverPilotOrInstructorRequired"` | same | `!canUpdateRecord` | none | `flight-edit-glider-form.html:123-143` |
| `glider.passengerPersonId` | client: not enforced; intent: required when `IsPassengerFlight` | `flightType.IsPassengerFlight` | `!canUpdateRecord` | none | `flight-edit-glider-form.html:145-172` |
| `glider.winchOperatorPersonId` | server: `startType==WinchLaunch` (`:1024-1030`); client: visibility only | `startType.IsWinchStart` | `!canUpdateRecord` | none | `flight-edit-glider-form.html:211-229` |
| `glider.startLocationId` | server (`:1090-1091`) | always | `!canUpdateRecord` | `localStorage.lastStartLocation` ‖ `myClub.HomebaseId` | `FlightsController.js:200` |
| `glider.ldgLocationId` | server (`:1093-1094`) | always | `!canUpdateRecord` | same chain; **mirrored** when `startLocationId` changes | `FlightsController.js:201, 650` |
| `glider.outboundRoute` | `startLocation.IsOutboundRouteRequired`; server `:1112-1123` checks against allow-list | `isOutboundRouteRequired` | `!canUpdateRecord` | none; "copy from last" reads `lastGliderOutbound` | `FlightsController.js:217-219, 703-704` |
| `glider.inboundRoute` | `landingLocation.IsInboundRouteRequired`; server `:1125-1135` | `isInboundRouteRequired` | `!canUpdateRecord` | none; "copy from last" reads `lastGliderInbound` | `FlightsController.js:704` |
| `glider.startTime` | server when `!noStartTimeInformation` (`:1084-1085`) | always | `!canUpdateRecord ‖ noStartTimeInformation` | none; "now" button → current time | `FlightsController.js:716-720, 808-812` |
| `glider.ldgTime` | server when `!noLdgTimeInformation` (`:1087-1088`) | always | `!canUpdateRecord ‖ noLdgTimeInformation` | none | `FlightsController.js:731-736, 814-817` |
| `glider.duration` | never (derived) | always | `!canUpdateRecord` | computed; editing back-computes ldg | `FlightsController.js:601-617, 738-743` |
| `glider.noStartTimeInformation` | n/a | always | `!canUpdateRecord` | `false` | toggle clears `startTime`; **also sets `tow.NoStartTimeInformation`** `FlightsController.js:808-812` |
| `glider.noLdgTimeInformation` | n/a | always | `!canUpdateRecord` | `false` | `FlightsController.js:814-817` |
| `glider.nrOfLdgs` | server: required iff `ldgTime` set (`:1102-1109`); `@Min(1)` | always | `!canUpdateRecord` | `1` on new; `1` on first `ldgTime` blur if unset | `FlightsController.js:203, 727` |
| `glider.engineStartOperatingCounterInSeconds` | never client | `selectedGliderAircraft.HasEngine` | `!canUpdateRecord` | reset on aircraft change when `resetEngineOperatingCounters=true` | `FlightsController.js:115-116, 128-136` |
| `glider.engineEndOperatingCounterInSeconds` | never client | `selectedGliderAircraft.HasEngine` | `!canUpdateRecord` | reset on aircraft change | `flight-edit-glider-form.html:418-429` |
| `glider.engineDuration` (computed) | never | `selectedGliderAircraft.HasEngine` | `!canUpdateRecord` | computed `end - start`, floored at 0 | `FlightsController.js:767-785` |
| `glider.flightCostBalanceType` | `ng-required="flightType.IsFlightCostBalanceSelectable"` | same | `!canUpdateRecord` | `1` on new | `flight-edit-glider-form.html:450-468`; `FlightsController.js:192` |
| `glider.invoiceRecipientPersonId` | `ng-required="PersonForInvoiceRequired"` | `PersonForInvoiceRequired && flightType.IsFlightCostBalanceSelectable` | `!canUpdateRecord` | none; **cleared** when `PersonForInvoiceRequired` becomes false | `FlightsController.js:562-573` |
| `glider.couponNumber` | never | `flightType.IsCouponNumberRequired` | `!canUpdateRecord` | none | `flight-edit-glider-form.html:494-502` |
| `glider.flightComment` | never | always | `!canUpdateRecord` | none | `flight-edit-glider-form.html:442-449` |
| `glider.isSoloFlight` | n/a (derived) | always (icon) | `!flightTypeCheckbox.isChangingAllowed ‖ !canUpdateRecord` | derived: `flightType.IsSoloFlight→true`; `IsPassengerFlight→false`; else preserve | `FlightsServices.js:75-98`; `FlightsController.js:111-124, 575-581` |
| `tow.aircraftId` | server-required when tow validated; client: no `required` | `startType==Towing` | `!canUpdateRecord` | `lastTowAircraftId` (copy-button only) | `FlightsController.js:147-152` |
| `tow.pilotPersonId` | server (`:1081-1082` on tow row) | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | `towPilotByAircraftId[aircraftId]` localStorage (copy-button) | `FlightsController.js:147-152, 350-352` |
| `tow.flightTypeId` | server (`:1099-1100` on tow) | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | `myClub.DefaultTowFlightTypeId` (set on aircraft selection) | `FlightsController.js:159` |
| `tow.startTime` | (server when `!noStartTimeInformation`) | `startType==Towing` | **always disabled** (mirrors `times.gliderStart`) | mirror of glider | `flight-edit-tow-form.html:95-101`; `FlightsController.js:370` |
| `tow.startLocationId` | server (`:1090-1091`) | `startType==Towing` | **always disabled** (`ng-disabled="true"`); mirrors glider | mirror of glider; default `myClub.HomebaseId` | `flight-edit-tow-form.html:145-157`; `FlightsController.js:205-206, 371, 650-654` |
| `tow.outboundRoute` | server (when required) | `isOutboundRouteRequired` | **always disabled**; mirrors glider | mirror | `flight-edit-tow-form.html:188-197`; `FlightsController.js:372` |
| `tow.ldgLocationId` | server (`:1093-1094`) | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | `myClub.HomebaseId` (via `resetTowFlightDefaults`) | `FlightsController.js:158` |
| `tow.ldgTime` | server when `!noLdgTimeInformation` | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId ‖ noLdgTimeInformation` | none | `FlightsController.js:745-758, 819-822` |
| `tow.noLdgTimeInformation` | n/a | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | `false` | **not** mirrored from glider | `FlightsController.js:819-822` |
| `tow.duration` | never | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | derived | `FlightsController.js:760-765` |
| `tow.nrOfLdgs` | server: required iff tow `ldgTime` set | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | `1` via `resetTowFlightDefaults`; `1` on first `formatTowLanding()` if unset | `FlightsController.js:160, 749` |
| `tow.inboundRoute` | server (when required) | `isInboundRouteForTowFlightRequired` | `!canUpdateRecord ‖ !tow.aircraftId` | none; "copy from last" reads `lastTowInbound` | `FlightsController.js:705` |
| `tow.flightComment` | never | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | none | `flight-edit-tow-form.html:225-233` |

**No `accountingRemark` field exists** in legacy glider/tow forms (grep confirmed). Out of scope; flag as a new requirement if business asks.

### Cross-field reactive rules

- **`startType` changes** → if Towing and `TowFlightDetailsData == null`, create empty `tow` block. Recompute `selectedStartType` for `IsWinchStart` visibility. `FlightsController.js:418-420, 666-673`.
- **`startType` changes to non-Towing** → tow block hidden via `@if`; tow data **kept in memory**, stripped at submit (`prepareForSaving :375-377`).
- **`glider.flightTypeId` changes** → recompute `selectedFlightType` → re-derive solo via `SoloFlightCheckboxEnablementCalculator.getSoloFlightCheckbox(...)` (`FlightsController.js:430-441`; `FlightsServices.js:75-98`):
  - `flightType.IsSoloFlight==true` → force `isSoloFlight=true`, checkbox `CHECKED`, not changeable.
  - `flightType.IsPassengerFlight==true` → force `isSoloFlight=false`, checkbox `UNCHECKED`, not changeable.
  - else → preserve existing, checkbox toggleable.
  - Also recompute `warnNumberOfSeatsInsufficientForFlightType` (`:583-588`).
- **`isSoloFlight` toggles to `true`** → `coPilotPersonId = undefined` (`FlightsController.js:425-427`).
- **`glider.aircraftId` changes** → set `selectedGliderAircraft`, `gliderCompetitionSign`. If `NrOfSeats===1 && !IsSoloFlight` → force `IsSoloFlight=true`. If `HasEngine` → fetch `AircraftOperatingCounters`, refresh `lastOperatingCounterFormatted`. Optionally clear engine counters. Recompute seat warning. (`FlightsController.js:110-145`.)
- **`glider.flightCostBalanceType` changes** → set `$scope.PersonForInvoiceRequired`. If false, **clear `glider.invoiceRecipientPersonId`** (`FlightsController.js:562-573`).
- **`tow.aircraftId` changes** → set `towplaneRegistration`. Run `resetTowFlightDefaults`: fill `tow.startLocationId / ldgLocationId / flightTypeId` from `myClub` defaults if empty; `tow.nrOfLdgs = 1`. (`FlightsController.js:163-188, 155-161`.)
- **`glider.startLocationId` changes** → `glider.ldgLocationId = glider.startLocationId` (overwrite); also mirror to `tow.startLocationId` and `tow.ldgLocationId`; recompute route requirements. (`FlightsController.js:649-656`.)
- **`glider.ldgLocationId` changes** → recompute `isInboundRouteRequired` (`FlightsController.js:658-660, 704`).
- **`tow.ldgLocationId` changes** → recompute `isInboundRouteForTowFlightRequired` (`FlightsController.js:662-664, 705`).
- **Location lookups resolve** → load `outboundRoutes` / `inboundRoutes` lists via `RoutesPerLocation` for selectize options (`FlightsController.js:690-701`).
- **`glider.noStartTimeInformation` toggles** → clear `times.gliderStart`; **propagates to `tow.NoStartTimeInformation`** (`FlightsController.js:808-812`). Asymmetric: glider landing toggle does *not* propagate (`:814-817`).
- **`glider.startTime` blur** → recompute `times.gliderDuration` and `times.towingDuration` (both anchored to glider start) (`FlightsController.js:709-714`).
- **`glider.ldgTime` blur** → recompute `gliderDuration`; **default `nrOfLdgs=1` if unset** (`FlightsController.js:723-729`).
- **`glider.duration` blur** → back-compute `times.gliderLanding = start + duration` (`FlightsController.js:738-743`).
- **`tow.ldgTime` blur** → recompute `towingDuration`; default `tow.nrOfLdgs=1` if unset (`FlightsController.js:745-751`).
- **`tow.duration` blur** → back-compute `times.towingLanding` (`FlightsController.js:760-765`).
- **Engine counters blur** → recompute `engineSecondsCounterDuration = max(0, end - start)` (`FlightsController.js:767-777`). **`engineDuration` blur** → recompute `engineEnd = engineStart + duration` (`FlightsController.js:779-785`).
- **Glider start + tow landing both valid** → `warnTowFlightLongerThanGliderFlight = gliderDuration < towDuration` (`FlightsController.js:590-599`). Warning only.

### Visibility-mode matrix

`flightAircraftType`: 1 = GliderFlight, 2 = TowFlight (derived, never user-edited), 4 = MotorFlight (separate route under `airmovements/`, out of S-062c).

| `startType` | `aircraftType` | Glider block | Tow block | Engine counters (glider) | Winch operator | Notes |
|---|---|---|---|---|---|---|
| Towing (1) | GliderFlight | shown | shown | iff `glider.HasEngine` | hidden | Default; `needsTowplane=true` (`FlightsController.js:418-420`). Tow row created+linked. |
| WinchLaunch (2) | GliderFlight | shown | hidden | iff `glider.HasEngine` | shown + server-required (`:1024-1030`) | `flight-edit-tow-form.html:2` `ng-if="needsTowplane"` hides tow column. |
| SelfStart (3) | GliderFlight | shown | hidden | iff `glider.HasEngine` | hidden | Self-launching motor glider — engine block usually applies. |
| ExternalStart (4) | GliderFlight | shown | hidden | iff `glider.HasEngine` | hidden | Server validates **no** tow linked (`:1017-1022`). |
| MotorFlightStart (5) | GliderFlight | shown | hidden | iff `glider.HasEngine` | hidden | Unusual; server accepts (`:1036-1039`). |
| any | MotorFlight | **N/A — separate route** `/airmovements/...` owned by **S-064**. Form structurally different. | | | | flag boundary. |

Engine-counter visibility is `glider.HasEngine`, independent of `startType` (`flight-edit-glider-form.html:394, 419, 431`).

### Disabled-state rules

- **Whole form**: disabled when `!flightDetails.CanUpdateRecord` (server-supplied). Derivation: `processState >= Locked && (!IsClubAdministrator || processState == DeliveryBooked)` → false; else true. Source: `FlightService.cs:1741-1770` (`SetFlightDetailsSecurity`); mirrored on overviews at `:1675-1687`.
- **Legacy server gap closed in S-062a**: `UpdateFlightDetails` only hard-blocks `DeliveryBooked` in legacy. New server rejects `Locked`/`DeliveryPrepared` for non-admins.
- **Delete button**: gated by `CanDeleteRecord`; same derivation. `DeliveryBooked` rejects at server (`:1308-1312`).
- **Tow sub-controls extra gate**: `tow.pilotPersonId`, `tow.flightTypeId`, `tow.ldgTime`, `tow.ldgLocationId`, `tow.nrOfLdgs`, `tow.inboundRoute`, `tow.flightComment`, `tow.duration` are additionally disabled when `!tow.aircraftId`. Cite: every `ng-disabled` in `flight-edit-tow-form.html:64, 90, 109, 135, 175, 185, 206, 220, 231`.
- **`tow.startLocationId` / `tow.startTime` / `tow.outboundRoute`**: **always** disabled (`ng-disabled="true"` / `disabled`) — mirrored from glider at submit (`flight-edit-tow-form.html:155, 100, 195`).
- **Time fields gated by their "no info" flag**: `glider.startTime` disabled when `noStartTimeInformation`; same for landing. (`flight-edit-glider-form.html:237, 263`; `flight-edit-tow-form.html:109`.)
- **Role-driven** (overriding above): `IsClubAdministrator` users can edit `Locked` / `DeliveryPrepared` / `DeliveryPreparationError` / `ExcludedFromDeliveryProcess` flights — everything except `DeliveryBooked`. Already encoded in `CanUpdateRecord`; SPA needs no separate role check.

### Default-value derivation

**New flight** (`/flights/new` → fetches `GET /flights/new-template` from S-062a → `FlightFormDefaults` applies any client-only overlays):

- Server returns a fully-populated draft with `myClub` defaults applied (port of `initForNewFlight`).
- Client-only overlays: `localStorage.lastStartLocation` if present takes precedence over server `myClub.HomebaseId` for start/ldg locations (parity).

**Copy** (`/flights/copy/:id`): server fetches `GET /flights/{id}/copy-template` (S-062a) which returns the cleared draft. Client applies the same `lastStartLocation` overlay.

**localStorage hydration** (workstation-scoped UX convenience):

- `lastTowAircraftId` (written at save `:353`) + `towPilotByAircraftId[aircraftId]` (`:350-352`) — hydrated **only on explicit "copy from last" button click** in tow aircraft field (`flight-edit-tow-form.html:36-38` → `copyTowingFromLast` at `:147-152`). Not auto-applied on form load.
- `lastStartLocation` (written `:359`) — **auto-hydrated** as default for both glider start/ldg and tow start/ldg on new flight (`:200-201, 205-206`).
- `lastGliderOutbound`, `lastGliderInbound`, `lastTowOutbound`, `lastTowInbound` — written on save (`:354-358`); hydrated only via per-field "copy from last" history button (`copyRouteFromLast` `:217-219`).

### Submit-time transformations

`prepareForSaving(flightDetails)` (`FlightsController.js:348-378`) — implemented by `FlightFormCoordinator.toDto(form)`:

1. **Persist localStorage** for next session: write `towPilotByAircraftId[towAircraftId]`, `lastTowAircraftId`, `lastTowOutbound/Inbound`, `lastGliderOutbound/Inbound`, `lastStartLocation` (`:348-359`).
2. **Compose datetimes** from `flightDate` + `times.gliderStart/gliderLanding/towingLanding` into ISO datetimes (`:364-366, 373`).
3. **Glider→tow sync** (always, before discard check): `tow.StartDateTime = glider.StartDateTime`; `tow.StartLocationId = glider.StartLocationId`; `tow.OutboundRoute = glider.OutboundRoute` (`:370-372`).
4. **Tow discard**: if `!needsTowplane(startType) || !tow.AircraftId` → `flightDetails.TowFlightDetailsData = undefined` (`:375-377`). Partial tow data the user filled in is dropped.

`mapFlightToForm(result)` on **load** — reverse-direction normalization (`:317-346`):

5. **Empty-Guid normalization**: `tow.AircraftId == '00000000-0000-0000-0000-000000000000'` → `null`; same for `tow.PilotPersonId` (`:319-324`). Server (S-062a) rejects empty UUIDs at the wire — client must normalize on load.

`flightTypeChanged()` / `flightCostBalanceTypeChanged()` / `recalcCheckboxState()` — applied at edit time, also takes effect before save:

6. **CoPilot clear when solo** — `flightTypeCheckbox.state === 'CHECKED'` → `glider.CoPilotPersonId = undefined` (`:425-427`).
7. **InvoiceRecipient clear when not required** — `PersonForInvoiceRequired` flips false → `glider.InvoiceRecipientPersonId = undefined` (`:562-573`).
8. **`IsSoloFlight` force-set** by aircraft seat count (`NrOfSeats===1 && !IsSoloFlight` → `true`) on aircraft change (`:121-124`).

Mass-assignment defense: form **does not** include `processState`, `operating_club_id`, `owner_id`, `validation_errors`, `version`, audit columns in the DTO at submit — server (S-062a) rejects them anyway.

<!-- amendment-2026-05-15b: start -->

## Amendment 2026-05-15b — Mobile-first / dense-desktop directive

The vision-doc amendment 2026-05-15b (see [`02-vision-and-constraints.md`](../02-vision-and-constraints.md) §C21–C24 + §F1–F16) designates the flight-edit form as **the** airfield hot-path screen alongside the flight list (S-062b). This story is the primary landing zone for that directive.

**Layered acceptance criteria (additive to existing AC list — do not remove the parity ACs):**

- **AC-DIR-1 (single-column responsive wizard at all sizes).** Wizard renders as a 3-step single-column flow at every viewport — mobile to desktop. No dense / multi-column variant; the dense-desktop layout originally specified by amendment 2026-05-15b is **dropped** (operator 2026-05-25). Sticky save bar anchored to viewport bottom on `<lg`; inline page-header actions on `≥lg`. (Vision §F2 still applies; §F3 dense layout supersedes-by-drop.)
- **AC-DIR-2 — dropped (2026-05-25).** Dense-desktop multi-column variant removed. Single responsive wizard satisfies both density profiles.
- **AC-DIR-3a (keyboard nav first pass — in this story).** Tab / Shift+Tab natural order across the wizard, Enter advances step / submits on last step, Esc cancel-with-dirty-confirm. `04d-keyboard-only.spec.ts` happy path asserts the form saves with zero mouse events at any viewport. (§F4, §2 NFR "keyboard-only completion" — applies universally now that dense is dropped.)
- **AC-DIR-3b (keyboard nav polish — deferred to S-062i).** Ctrl+D = save+copy; number keys 1–5 select the most-common flight-types. Linux Firefox Ctrl+D collides with browser bookmark — needs `preventDefault` + browser-target test. See `S-062i-flight-edit-keyboard-polish.md`.
- **AC-DIR-4 ("Copy from Last" preserved as first-class).** Per-field "Copy from Last" buttons backed by the new `af-user-preferences-service` (Dexie, `sub`-scoped — replaces raw localStorage per `alpenflight/web/CLAUDE.md` policy). They are not replaced by AC-DIR-5. (C24, §F5.)
- **AC-DIR-5 (smart defaults from server context).** When the form opens blank (no localStorage hint, no copy), the SPA calls `GET /api/v1/flights/last-context?aircraftId=<club-default>&date=<today>` (added in S-062a) and patches with the last-saved field combo. Smart defaults **never** overwrite an explicit "Copy from Last" action. Empty response → falls back to `flight-form-defaults.ts`. (§F6, §F7.)
- **AC-DIR-6 (recency-biased autocompletes).** All dropdowns (aircraft, pilot, observer, passenger, location, route) surface "recently used by this user, last 7 days" at the top of the list before the rest of the catalog. Consumes `<fls-autocomplete>` primitive from S-008. (§F8.)
- **AC-DIR-7 (inline validation, not on-blur).** Errors pin next to the offending field; update as the user types / moves focus. Soft pref §4. Supersedes legacy on-blur + top-message-bar pattern. (§F9.)
- **AC-DIR-8 (native input types).** `<input type="time">` (native mobile picker); `<input type="date">`; `inputmode="numeric"` for counters / nrOfLdgs. The `<fls-time-now-button>` primitive (S-008) wraps the legacy "Set Now" semantics on top of native time inputs. No text-with-format-on-blur. (§F10, §F14.)
- **AC-DIR-9 (auto-save draft to IndexedDB — deferred to S-062h).** Form debounce-saves (500 ms) the in-progress draft to IndexedDB via `af-user-preferences-service` on every field change. On connection loss, queued via PWA service worker (ADR 0015). On reload, draft restored with "continue from draft / start fresh" prompt via `<af-dialog>`. (§F12.) Plumbs in this story (Dexie store provisioned); full draft service + restore prompt ship in **S-062h**.
- **AC-DIR-10 (touch-target compliance).** Primary actions ≥ 44 × 44 CSS px hit area at every viewport. Icon-only secondary actions ≥ 28 × 28 CSS px. Enforced by S-008 primitives kit; verified by Playwright bounding-rect assertion (axe-core rescinded per vision amendment 2026-05-20d). (§2 NFR "touch targets".)
- **AC-DIR-11 (time-to-log benchmark).** Scripted Playwright "stopwatch" test logs a typical glider-with-tow flight on desktop (1280 × 800) in ≤ 60 s and on phone viewport (360 × 640) in ≤ 90 s. Recorded per release; informational, not a blocking gate. (§2 NFR "time-to-log".)
- **AC-DIR-12 (concurrency UX, reconciled — deferred to S-062h).** Two codes split by source: **412** (stale-version `If-Match` mismatch from S-067 once `@Version` lands) → inline diff dialog via `<af-dialog>` with per-field keep-mine / keep-theirs, draft visible, no auto-retry. **409** (state-gate reject from S-062a `DELIVERY_BOOKED`, or `ObjectOptimisticLockingFailureException` race) → non-blocking toast with "Reload latest" action; no diff because state-gate is policy not data. Earlier AC9 toast wording is superseded — toast remains the 409 fallback. This story plumbs the `If-Match` round-trip and a placeholder 412 toast; the diff dialog ships in **S-062h**.
- **AC-DIR-13 (smooth conditional sections — deferred to S-062i).** Dependent fields (e.g. tow step empty-state when StartType ≠ Towing; instructor when `InstructorRequired`) appear/disappear via Signal-Store render control; 150 ms slide-in (honors `prefers-reduced-motion`); focus moves to first new field. No layout jank. (§F15.)
- **AC-DIR-14 (marginal-connectivity graceful degradation — deferred to S-062h).** At simulated 200 ms RTT + intermittent loss: dropdown data served from Signal Store cache (S-006); save attempts queue via service worker (ADR 0015); no spinner > 3 s blocks the user. (§2 NFR.) Tested by `04h-marginal-3g.spec.ts` in S-062h.

**Refinement status flag:** Re-refined on 2026-05-25 with the amendment + operator decisions baked into the Design / Edge / Security / Test / Performance sections. Story formally split via decompose on 2026-05-25 into S-062c (core: wizard + paired-create + Copy-from-Last + smart-defaults + first-pass keyboard), [S-062h](S-062h-flight-edit-resilience.md) (drafts + 412 inline diff + marginal-3G), and [S-062i](S-062i-flight-edit-keyboard-polish.md) (Ctrl+D + 1–5 quick-select + slide-in focus).

**Inputs picked up from sibling stories:**

- S-008 — `<fls-autocomplete>` with recency-bias, `<fls-time-now-button>`, density tokens, breakpoint utilities, touch-target lint.
- S-007 — inline-validation + native-input form convention.
- S-062a — `GET /api/v1/flights/last-context` endpoint.
- S-006 — Signal-Store-driven conditional render + aggressive prefetch on app start.
- S-067 + ADR 0014 — conflict + offline machinery.
- S-067 — `@Version` column + 409 / 412 surfacing.

<!-- amendment-2026-05-15b: end -->
