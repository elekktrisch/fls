---
id: J-2b
title: Flights hardening — new-flight visibility + edit-form validation + migration fidelity
epic: E-07
status: in_progress
started_at: 2026-06-20
journey0: false
carved: true
depends_on: [J-2, J-27]
rolls_up: []          # hardening sprint — #229 (issue) + audit findings + the FLIGHT-FIDELITY rider; no horizontal S-story
acceptance:
  - "[happy] Creating a new flight via the form persists it (confirmed saved — #229 amendment)."
  - "[edge] A future-dated flight is discoverable in the list — match legacy's default date-range behavior; if legacy hides future flights by default, surface a clear affordance (verify legacy parity)."
  - "[happy] Re-opening a saved flight shows all populated required fields as VALID — the edit form has client validators WIRED (currently zero); validity initializes against the loaded values."
  - "[key-error] The flight edit form shows as-you-type inline errors (the J-6b liveFieldErrors bar) on required fields + Save is gated on validity (server @NotNull/@Min stays the safety net)."
  - "[migration/parity] the migrated TestClub glider flights carry their legacy crew + flight-type (FLIGHT-FIDELITY: confirm delivery-creation-test-parity.spec.ts:577's green is for the right reason; explain/fix the absent §5 flight + the spurious HB-3407/30)."
screen: /flights — hardens the existing J-2 screen (no redesign)
headless_pulled_in: none
migration: Flight — re-verify migration fidelity (crew + flight-type round-trip) per the FLIGHT-FIDELITY rider; no new mapper
parity_test: alpenflight/web/e2e/tests/real-idp/flight-migration-parity.spec.ts (FLIGHT-FIDELITY) + alpenflight/web/e2e/tests/flights/ (J-2 specs + a new-flight create→reopen→validate spec)
mock_test: alpenflight/web/e2e/tests/flights/   # per-push mock-e2e runs ONLY these; prior journeys' mock specs run at the §4 gate + nightly
adr_refs: [0024, 0008, 0022]
---

## Context

Operator-filed P0 **#229** ("New flight form broken"), amended: a new flight **is** saved, but
(a) **future-dated flights are hidden by the default list date-range** (maybe legacy-true — verify),
and (b) **re-opening a saved flight shows its required fields as INVALID**. Root of (b): the flight
edit form has **zero client validators** — `FlightValidator`/`FlightCompositeValidator` author the full
rule set but are **never invoked** on any production path (form-validation-parity audit, "dead validator").
This hardening sprint wires flight-form validation (the J-6b as-you-type bar), settles future-flight
visibility against legacy, and verifies the migrated-flight fidelity J-27 flagged. No new feature
(hardening-sprint shape — J-6b/J-26 precedent; the ≤70% debt-burndown window covers it).

## Spec must assert

1. **New-flight create persists** (regression guard for #229) — create via the form → the flight exists
   (GET/list with the right date range returns it).
2. **Future-flight visibility** — determine legacy's default flight-list date range (legacy-oracle at ship
   time). If legacy hides future flights by default, MATCH it but make them discoverable (the date-range
   filter surfaces them, ideally a hint that future flights exist); if not legacy-true, fix the default range.
3. **Edit-form validity on load** — re-open a saved flight → every populated required field renders VALID
   (not invalid). The form must carry client validators (it has none today) initialized against loaded values.
4. **As-you-type + Save-gating** — required fields show debounced inline errors via the shared
   `liveFieldErrors()` (J-6b bar); Save is gated on client validity; server `@NotNull`/`@Min`/`@Valid` stays
   the safety net (don't regress it).
5. **Migration fidelity (FLIGHT-FIDELITY)** — the migrated glider flights carry their legacy crew +
   flight-type so `delivery-creation-test-parity.spec.ts:577` is green for the RIGHT reason (a base-seed
   Schulung filter doesn't silently fail to match); explain the absent §5 flight + the spurious HB-3407/30.

## Decisions (ship-time)

- **Default flight-list range = `today..today`** (legacy parity — `FlightsController.js:51-54`). The
  current AlpenFlight default is `null/null` (show-all, `flight.store.ts:97-99`); this journey sets the
  today-default deliberately so the daily list stays short.
- **Future/off-today affordance = post-save jump (Option B).** No banner/count (Option A) and no preset
  chip (Option C). When a saved flight's date ≠ the active range (i.e. ≠ today), the post-save flow
  surfaces it and offers "View it →" that widens the range to include that date. This defuses #229.
- **Minimal valid glider field set** (oracle, `Flight.cs:574-584`): Aircraft, Pilot, Start+Ldg time,
  Start+Ldg location, StartType, FlightType, NrOfLdgs≥1. Server `ValidateFlight` sets `Invalid` but does
  NOT reject the save — so #229(b) is a FORM-validity display bug, not a persistence bug.
- **Crew/flight-type shape** (oracle): flight-type = scalar FK on `Flight`; crew = `FlightCrew` junction
  rows keyed by `FlightCrewType` enum (1=Pilot, 2=CoPilot, 3=Instructor, …).
- **Save-gating = legacy parity** (operator pick, supersedes the T-04 full-set gate): Save is gated ONLY on
  the legacy-client-required set — **FlightDate + glider Aircraft + Pilot**. The rest of the minimal-valid set
  (start/ldg time, start/ldg location, StartType, FlightType, NrOfLdgs≥1) keep their as-you-type inline errors
  (T-05) and mark the flight incomplete/Invalid (legacy `ProcessState=Invalid` parity), but do NOT block Save.
  Rationale: legacy saves incomplete flights as Invalid (airfield hot-path: log on launch, complete after
  landing); the legacy client gates Save only on Date + Aircraft. T-08 revises T-04 to this.
- **AC5 / FLIGHT-FIDELITY = resolved** (T-06, no fix needed): the Flight mapper round-trips flight-type
  (scalar FK) + crew (one `FlightCrew` row/role); `delivery-creation-test-parity` is green for the right
  reason. The §5-absent flight is a fixture IF-guard data-condition; the HB-3407/30 line was a transient
  fanout-snapshot misread (not reproducible from the seed). Fast round-trip ITs added in `migration-bundle:check`.

## Tasks

- [x] **T-01 — Spec stub + gallery scaffold + red-first repro.** Author the J-2b Playwright spec structure
  (selectors + flow: create→persists, off-today flight + post-save jump, reopen→valid, as-you-type errors)
  with thin assertions. Scaffold the per-journey gallery page (current-journey-only model) + link from the
  index. Red-first reproduce #229(b) reopen-invalid + capture the actual current date-range default behavior.
  - RED-FIRST (empirical, mock-auth chromium): **#229(b) IS reproduced.** Reopening a FULLY-populated glider
    flight renders stale "Entry required." inline errors against populated required controls (1 on the launch
    step, 2 on the glider step — flightDate/aircraft/pilot) even though Save is ENABLED (`formInvalid()`
    computes VALID overall). Root: `liveFieldErrors()` (`inline-validation.ts:120`) snapshots `control.errors`
    untracked at construction — BEFORE `flights-edit.page.ts:856` re-emits post-hydrate — and the `patch()`
    hydration runs `emitEvent:false`, so the per-field error signal keeps its pre-hydrate `{required}` value.
    T-04/T-05 fix.
  - DATE-RANGE DEFAULT (empirical): AlpenFlight currently shows future/off-today flights — `flight.store.ts:97-99`
    is `dateFrom/dateTo: null` and `paramsOf` (`flight.store.ts:129-134`) OMITS `from`/`to` when null, so the
    list `GET /flights` carries NO date filter (show-all). T-03 sets the `today..today` default (legacy parity).
- [x] **T-02 — Scope per-push gate to J-2b.** Re-point the per-push heavy (real-idp) lane to J-2b's spec only;
  prior journeys run mock-IdP (full real-idp regression → nightly + §4 gate). Standing slot.
- [x] **T-03 — Flight-list default range `today..today` + Option B post-save jump.** `flight.store.ts`
  default `dateFrom/dateTo = today`; list range filter reflects it. Post-save: if the saved flight's date is
  outside the active range, offer "View it →" widening the range to that date. (AC1 regression guard, AC2.)
- [x] **T-04 — Wire flight edit-form client validators (valid-on-load + Save-gating).** `flight-form.model.ts`
  + coordinator: wire the minimal-valid-glider rule set; validity initializes against loaded values (valid-on-
  load fix for #229(b)); Save gated on client validity. Reconcile the dead `FlightValidator`/
  `FlightCompositeValidator` (wire the needed subset or delete the rest). Server `@NotNull`/`@Min`/`@Valid`
  stays the safety net. (AC3 + AC4 foundation.)
- [x] **T-05 — As-you-type inline errors via `liveFieldErrors()`.** Wire the shared J-6b bar
  (`inline-validation.ts:120`) into the flight edit form's required fields; debounced inline errors. (AC4.)
- [x] **T-06 — Migration fidelity (FLIGHT-FIDELITY).** Verify the Flight mapper round-trips crew (FlightCrew
  rows by type) + flight-type (FK); make `delivery-creation-test-parity.spec.ts:~577` green for the RIGHT
  reason (no silently-failing Schulung filter); explain/fix the absent §5 flight + spurious HB-3407/30. (AC5.)
- [ ] **T-08 — Save-gating → legacy parity.** Revise T-04: gate Save ONLY on FlightDate + glider Aircraft +
  Pilot. The remaining minimal-valid fields keep their inline errors (T-05) + mark the flight incomplete/Invalid
  but do NOT contribute to the Save-disable. New-flight create from the empty template must persist with just
  Date+Aircraft+Pilot. Don't regress the server safety net. Fix the create-persists + gating specs. (AC1, AC4.)
- [ ] **T-07 — Thicken spec + drive real-idp green locally.** Thicken the J-2b spec to full real assertions
  from the oracle; `e2e-driver` drives the real-idp spec green on the local stack before the §4 CI gate.

## Notes

- **#229 amendment is load-bearing:** it's NOT a save failure. (a) future-flight default-range visibility
  (verify legacy parity — don't "fix" legacy-true behavior, surface it); (b) the real bug is the missing
  client validators → invalid-on-load.
- **Seams** (non-binding, for `/do-ship`; one component/store each):
  - Flight edit-form validation wiring → `flights/edit/flight-form.model.ts` + `flight-form.coordinator.ts`
    + the shared `shared/util/form/inline-validation.ts:120` `liveFieldErrors()`; reconcile the dead
    `FlightValidator`/`FlightCompositeValidator` (wire the rule subset the form needs, or delete the rest).
  - Flight-list default date-range → `flights/flight.store.ts` + the list component's range filter.
  - Migrated-flight fidelity → the Flight migration mapper (crew + flight-type round-trip), re-verified via
    the fanout `flight-migration-parity` + the J-9 engine block.
- **Riders folded here** (`_BOYSCOUT.md`): **#229** (the lead); **FLIGHT-FIDELITY** (J-27-filed); the
  **as-you-type batch** for flights (audit §2b — flights have no validators at all); **COMMENT-STRIP**
  per-touch on the flight files edited. **GALLERY-SIMPLIFY + WORKFLOW-SLIM remainder** (the deferred
  burndown) are CI-surface candidates — `/do-ship` sizes them per the gate; don't let them swamp the P0.
- **No flights design reference** beyond the global `design-reference/AlpenFlight.html`; J-2 already built
  the screen — this hardens it, no redesign.
- Hardening sprint (no new feature) — the J-6b/J-26 exception shape, covered by the debt-burndown window.

## Assumptions made

1. `depends_on: [J-2, J-27]` — J-2 built the flight screen this hardens; J-27 repaired the fanout gate the
   FLIGHT-FIDELITY verification runs on.
2. Future-flight default-range parity is resolved at ship time via `legacy-oracle` (legacy's default
   flight-list date range); if legacy-true, match + surface, don't silently "fix".
3. The edit-form invalid-on-load is the missing-client-validators gap (audit "dead validator"), not a
   data-load bug — to be confirmed red-first at ship time.
