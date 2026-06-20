---
id: J-2b
title: Flights hardening — new-flight visibility + edit-form validation + migration fidelity
epic: E-07
status: done
started_at: 2026-06-20
done_at: 2026-06-20
journey0: false
carved: true
depends_on: [J-2, J-27]
rolls_up: []          # hardening sprint — #229 (issue) + audit findings + the FLIGHT-FIDELITY rider; no horizontal S-story
acceptance:
  - "[happy] Creating a new flight via the form persists it (confirmed saved — #229 amendment)."
  - "[edge] A flight dated off today is discoverable — list defaults to today..today (legacy parity); an off-range saved flight surfaces via the post-save 'View it →' jump (Option B)."
  - "[happy] Re-opening a saved flight shows all populated required fields as VALID — client validators initialize against the loaded values."
  - "[key-error] The flight edit form shows as-you-type inline errors (the J-6b liveFieldErrors bar) on required fields + Save is gated on the legacy-parity set (Date+Aircraft+Pilot); server @NotNull/@Min stays the safety net."
  - "[migration/parity] the migrated TestClub glider flights carry their legacy crew + flight-type (FLIGHT-FIDELITY)."
screen: /flights — hardens the existing J-2 screen (no redesign)
headless_pulled_in: none
migration: Flight — re-verified migration fidelity (crew + flight-type round-trip); no new mapper
parity_test: alpenflight/web/e2e/tests/real-idp/flight-migration-parity.spec.ts (real chain + FLIGHT-FIDELITY) + alpenflight/web/e2e/tests/flights/ (mock inner-loop)
adr_refs: [0024, 0008, 0022]
---

## Context

Operator-filed P0 **#229** ("New flight form broken"), amended: a new flight **is** saved, but
(a) future-dated flights were not visible, and (b) re-opening a saved flight showed its required fields
as INVALID. Both turned out to be display/visibility issues, not a persistence bug:

- (a) is the flight-list date range. Legacy defaults to **today..today**; AlpenFlight had defaulted to
  show-all. This journey adopts the legacy today-default deliberately (short daily list).
- (b) is NOT a missing-validator gap (the audit's "dead `FlightValidator`/`FlightCompositeValidator`"
  premise was wrong — **no such symbols exist**; J-26 already wired Date/Aircraft/Pilot). The real bug:
  `liveFieldErrors()` snapshotted `control.errors` at construction, before the form re-emitted post-hydrate,
  and the `patch()` hydration ran `emitEvent:false` — so populated fields kept a stale `{required}` error.

## Spec must assert

1. **New-flight create persists** (#229 regression guard) — create via the form with the legacy-parity set
   → the flight exists (re-GET via the 201 Location, not the SPA-evicted POST body).
2. **Off-today visibility** — list defaults to today..today; an off-range saved flight surfaces via the
   post-save "View it →" jump (Option B) that widens the range to the saved date.
3. **Edit-form validity on load** — re-open a fully-populated saved flight → every populated required field
   renders VALID (no stale inline errors).
4. **As-you-type + Save-gating** — required fields show debounced inline errors via the shared
   `liveFieldErrors()` bar; Save is gated on the legacy-parity set; server `@NotNull`/`@Min`/`@Valid` stays
   the safety net.
5. **Migration fidelity (FLIGHT-FIDELITY)** — the migrated glider flights carry their legacy crew +
   flight-type (verified by a real fanout-parity run + fast round-trip ITs).

## Decisions (ship-time)

- **Default flight-list range = `today..today`** (operator pick; legacy parity — `FlightsController.js:51-54`).
- **Off-today affordance = post-save jump (Option B)** only (no count banner, no preset chip): an off-range
  saved flight surfaces a "View it →" that widens the active range to its date.
- **Save-gating = legacy parity:** gated ONLY on **FlightDate + glider Aircraft + Pilot**. The rest of the
  minimal-valid set (start/ldg time, start/ldg location, StartType, FlightType, NrOfLdgs≥1) keep as-you-type
  inline errors and mark the flight incomplete/Invalid (legacy `ProcessState=Invalid` parity) but do NOT block
  Save — legacy saves incomplete flights as Invalid (log on launch, complete after landing).
- **Filter-aware empty state:** with the today-default the list is usually empty due to the active filter, so
  it shows "No matching flights" when a range/filter is narrowing; a true-empty message only when unfiltered.
- **Parity exclusion:** the tow-flight sub-group is deliberately NOT Save-gating — gating Save on a missing
  tow would regress existing tow-discard parity.
- **AC5 / FLIGHT-FIDELITY = resolved:** the Flight mapper round-trips flight-type (scalar FK on `Flight`) +
  crew (one `FlightCrew` row per role, keyed by the `FlightCrewType` enum). The §5-absent flight is a fixture
  IF-guard data-condition; the HB-3407/30 line was a transient fanout-snapshot misread (not seed-reproducible).

## Tasks

- [x] **T-01** — Spec stub + per-journey gallery scaffold + red-first repro of #229(b).
- [x] **T-02** — Scope the per-push heavy lane to J-2b's spec (prior journeys mock-IdP; full regression nightly + gate).
- [x] **T-03** — Flight-list `today..today` default + Option B post-save jump.
- [x] **T-04** — Wire the minimal-valid glider validators + valid-on-load (`revalidateTree`).
- [x] **T-05** — As-you-type inline errors via `liveFieldErrors()` + the #229(b) display fix.
- [x] **T-06** — Migration fidelity: verify crew + flight-type round-trip; fast round-trip ITs in `migration-bundle:check`.
- [x] **T-08** — Save-gating → legacy parity (Date+Aircraft+Pilot only).
- [x] **T-07** — Thicken the real-chain spec to full assertions; drive real-idp green; populate the gallery.
- [x] **T-09** — Gate-red fixes: correct the migrated-block `beforeAll` timeout hook + range-widen the specs that assert historical flights under the today-default.
- [x] **T-10** — Filter-aware flights-list empty state.
- [x] **T-11** — Deployed-journey gallery guard checks the proof-context's own (populated) per-journey page.

## Outcome

Shipped green. Real-idp clean-seed proof + the `fan-out parity` migration done-bar both executed green;
the per-journey proof page renders the paired flights shots + pass video. #229 closed: today-default for the
daily list, Option B jump for off-today saves, valid-on-load via `revalidateTree()` + the `liveFieldErrors`
fix, legacy-parity Save-gating, filter-aware empty state. No new mapper; crew + flight-type fidelity verified.

**Follow-ups (for /do-retro · /do-plan):**
- **GALLERY-SIMPLIFY:** the all-journeys `previews/index.html` still links the fanout's `legacy-parity/J-<n>/`
  pages (videos, no clean-seed UI shots); the canonical bookmark is the per-journey proof page. Collapse the
  stale index linkage to the simplified per-journey model.
- **AEROTOW clean-seed flake:** the clean-seed AEROTOW `flight-edit-startLocation` af-select lacks a search
  term (unlike the glider helper); intermittently flakes the option render under RAM pressure. Latent — give
  it a deterministic search term.
