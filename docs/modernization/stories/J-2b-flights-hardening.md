---
id: J-2b
title: Flights hardening — new-flight visibility + edit-form validation + migration fidelity
epic: E-07
status: todo
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
parity_test: alpenflight/web/e2e/tests/flights/ (J-2 specs + a new-flight create→reopen→validate spec) + alpenflight/web/e2e/tests/real-idp/flight-migration-parity.spec.ts (FLIGHT-FIDELITY)
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
