---
id: J-2c
title: Flights list date-range filter — default visibility + working controls + styling
epic: E-07
status: in_progress
started_at: 2026-06-21
journey0: false
carved: true
depends_on: [J-2b]
rolls_up: []
acceptance:
  - "[happy] On initial /flights load the date-range filter INPUTS display today..today (the store's legacy-parity default surfaces in the visible control), captured as an initial-state proof screenshot."
  - "[happy] Editing the range by MOUSE (open the picker, pick from + to) updates the filter and refetches the list — with ZERO uncaught browser-console errors; proof screenshot of the active control."
  - "[happy] Entering dates by KEYBOARD (type into the from/to fields) updates the filter and refetches the list — with ZERO uncaught browser-console errors; proof screenshot of the active control."
  - "[key-error] No uncaught console error is emitted during any date-control interaction — the spec subscribes to page 'console'/'pageerror' and fails on the first error."
  - "[edge] The from-field renders a single clean underline on focus/edit — no duplicate/stray underline."
screen: /flights — hardens the J-2/J-2b date-range filter (no redesign)
headless_pulled_in: none
migration: N/A — UI fix (no entity, no mapper, no fanout gate)
parity_test: alpenflight/web/e2e/tests/real-idp/flights-date-filter-parity.spec.ts (real-idp proof capture of the 3 states — initial / mouse-edit / keyboard-entry — for the gallery; alpenflight/web/e2e/tests/flights/flights-list.spec.ts is the mock inner-loop)
adr_refs: [0024, 0022]
---

## Context

J-2b adopted the legacy `today..today` default range (`flight.store.ts:113-116`) and the list correctly
**fetches** today's flights — but the date-range filter **control itself is broken**: (1) the visible
from/to inputs do NOT display the today default on load, (2) interacting with the control throws a burst of
browser-console errors and "doesn't work at all", and (3) the from-field shows a duplicate/stray underline
when edited. The daily list is the hot path; an unusable, visibly-wrong filter is a P1 regression. This
journey makes the filter **work and look right** — default visible, mouse + keyboard editing functional and
error-free, clean styling — proven by screenshots of each state.

## Spec must assert

1. **Default visible** — load `/flights`; the date-range inputs render **today** in both from and to (not an
   empty placeholder). The store default already filters the list to today; the visible control must agree.
   Capture the initial-state screenshot for the gallery.
2. **Mouse editing works** — open the picker, select a from + a to date; the list refetches for the new range;
   **no uncaught console error** fires. Capture the active-control screenshot.
3. **Keyboard entry works** — type dates into the from/to fields; the list refetches; **no uncaught console
   error** fires. Capture the active-control screenshot.
4. **Zero console errors** — the spec attaches a `page.on('console', …)`/`page.on('pageerror', …)` guard for
   the whole filter-interaction flow and fails on the first `error` (this is the functional proof that the
   controls actually work, not just that the screenshot looks plausible).
5. **Clean underline** — on focus/edit the from-field shows a single underline (the duplicate stroke is gone).

## Notes

- **No design reference** for this filter: `docs/modernization/design-reference/AlpenFlight.html` +
  `screens-logbook.jsx` document the flights/logbook screen but **not** a date-range filter (the three-column
  Date-range / Air-state / Aircraft-type filter row is a pragmatic addition). So the fix matches the existing
  `af-*` control conventions + clean focus styling, not a pixel oracle. Flag if a reference is added later.
- **Root-cause seams (from the codebase map — non-binding, one seam each):**
  - The **`af-date-picker` organism** (`alpenflight/web/src/app/shared/ui/organisms/af-date-picker/af-date-picker.component.ts`)
    — it dual-registers `value = model<DateValue>(null)` AND `ControlValueAccessor`; the flights filter binds via
    `[value]="dateRangeValue()"` + `(valueChange)` (`flights-list.page.ts:250-258,553-563`), a path that may
    bypass `writeValue()` so the today default never reaches the picker and interaction misbehaves. The binding
    contract (signal `[value]` vs CVA) is the prime suspect for both the empty-on-load AND the broken-controls
    symptoms — fix it once on the organism (narrow blast radius: used only by the flights filter + the dev
    primitives demo, no other production screen).
  - The **initial-value conversion** `localDateFromIso()` (`shared/util/.../format-date.ts:53-59`) → the
    `dateRangeValue` computed collapses to `null` if either bound is null/malformed (timezone edge) — verify it
    yields today on mount.
  - The **focus underline** — `styles.css:156-168` puts a single border on `.ant-picker`; the extra stroke is
    likely a focus `::after`/dual-field separator or an `af-form-field` wrapper border stacking on ng-zorro's.
    Inspect live (DevTools) to confirm a true double-stroke vs the range separator before "fixing".
  - **Console-error reproduction is live-only** — the static map could not pin the throw; `/do-ship` drives the
    real deployed control (e2e-driver) and reads the actual console errors, then anchors the fix on them (do NOT
    guess the throw from the component source).
- **Existing tests / selectors:** `alpenflight/web/e2e/tests/flights/flights-list.spec.ts` already exercises the
  picker→store→server round-trip (AC11/T-13 regression, `getByTestId('flights-date-range')` +
  `.locator('input')`, overlay via `.cdk-overlay-container .ant-picker-panel-container`). Extend it; add the
  per-state proof captures. The range picker exposes two `<input>`s inside one `.ant-picker` (no per-field
  testid) — the from/to are `.locator('input').first()/.nth(1)`.
- **Riders to fold (touch this surface):**
  - **[AEROTOW-SELECT-FLAKE]** — give the clean-seed AEROTOW `flight-edit-startLocation` select a deterministic
    search term (it flakes under RAM pressure); this is a flights/e2e touch (`_BOYSCOUT.md`).
  - **a11y/console cleanup on the same row:** `af-form-field for="FlightDateRange"` has no matching control id;
    `af-select inputId="…"` (`flights-list.page.ts:262`) is passed but `af-select` defines no `inputId` input
    (silently dropped). Tidy the label/id wiring while in this file.
  - Per-touch **COMMENT-STRIP / HISTORY→GIT** + the J-2c gallery slice.
- **Gate shape:** migration N/A ⇒ **no fanout gate**; the done bar is clean-seed real-idp green + the 3 gallery
  proof screenshots (initial / mouse / keyboard) + zero-console-errors. The fast mock lane (`flights-list.spec.ts`)
  is the inner loop.

## Tasks

- [x] **T-01** — Spec stub + gallery scaffold: author the J-2c real-idp proof spec driving the 3 states
  (initial today-default visible, mouse-edit, keyboard-entry) with a `console`/`pageerror` zero-error guard
  (thin asserts, **red-first** — reproduces the live bug); scaffold the J-2c per-journey gallery page
  (`expected-shots.json` entry + captures tagged `journey:'J-2c'`).
- [x] **T-02** — Scope the per-push heavy lane to J-2c's real-idp proof spec (point the frontmatter
  `parity_test` selection at it; prior journeys → mock-IdP; full regression → nightly + the §4 gate).
- [x] **T-03** — Fix the `af-date-picker` binding (the core): make the `[value]`-signal + `(valueChange)` path
  honor `ControlValueAccessor`/`model()` so the `today..today` default surfaces in the inputs AND mouse +
  keyboard editing work with ZERO console errors. **Reproduce the live errors first** (run T-01's spec / the
  dev server) and anchor the fix on the actual console output — do NOT guess from the component source.
- [x] **T-04** — Styling + a11y tidy on the same row: remove the duplicate from-field underline on focus/edit
  (clean single stroke); fix `af-form-field for="FlightDateRange"` (no matching control id) + drop the
  silently-ignored `af-select inputId=` (`flights-list.page.ts:262`).
- [x] **T-05** — Thicken the spec to full assertions; drive the real-idp proof spec green LOCALLY; populate the
  gallery (initial / mouse / keyboard shots + pass video). Fold the **[AEROTOW-SELECT-FLAKE]** rider — give the
  clean-seed AEROTOW `flight-edit-startLocation` select a deterministic search term (flights/e2e touch).
- [x] **T-06** — Gate-red fix (the operator's console-error symptom, surfaced by the §4 guard): remove the
  meta-incompatible `frame-ancestors 'none'` from the `<meta http-equiv="Content-Security-Policy">` at
  `alpenflight/web/src/index.html:24` (browsers reject it in `<meta>` → console.error on every load); scan the
  same CSP for any other header-only directive that would also throw; the real frame-protection (response
  header) stays S-041's job — leave a boyscout rider so the intent isn't lost.
- [x] **T-07** — Shared suite-wide `afterEach` no-console-errors guard (operator request): a reusable Playwright
  fixture that collects `console`(error)+`pageerror` per test and asserts none in teardown, applied GLOBALLY
  (all e2e projects) with a curated benign allowlist + a per-test opt-out for deliberate-error cases (403/412/404
  paths). Adopt it in the J-2c spec (replace the inline guard, no duplication). Genuine app console errors it
  surfaces at the full-regression gate are FIXED, not allowlisted away.
  - Infra: `e2e/tests/_helpers/console-guard.ts` exports the shared `test` (auto fixture asserting the
    per-test error bag is empty in teardown) + `watchConsoleErrors(page, testInfo)` for spec-owned context
    pages + `allowConsoleErrors(testInfo, …)` opt-out. J-2c spec adopts it.
- [x] **T-08** — Gate-red fix (gallery plumbing, not journey behavior): the deployed-journey guard reds because
  `generate-previews-index.mjs` `branch` source probes `proof-preview/<branch>/legacy-parity/<jid>/` (a subpath
  that exists only in the fanout context, not `proof`), so J-2c's index row stays PENDING. Make the proof-context
  branch probe also check `proof-preview/<branch>/<jid>/` (mirror `proof-gallery-links.spec.ts:875-879`'s
  `GALLERY_PROOF_CONTEXT==='proof'` branch). Targeted probe fix only — the full GALLERY-SIMPLIFY collapse stays
  its own burndown rider.
- [x] **T-09** — Suite-wide console-guard rollout (operator chose: roll out now): flip the ~43 specs still
  importing raw `@playwright/test` to the shared `test` (one-line import swap each, auto fixture), and declare
  `allowConsoleErrors(…)` in the ~17 specs that `route.fulfill` an error status (+ optimistic-concurrency /
  409 / 412 paths). Then drive the §4 full-regression gate so the latent app console errors surface and get
  FIXED (unregistered `af-icon` names, uncaught HTTP errors) — not allowlisted away. Latent-error fixes that
  cluster become T-10+.
- [x] **T-10** — Full-suite gate-red fix (mined from run 27907112486): the mock-auth suite runs with **no
  backend**, so unmocked `/api/v1` reference GETs fall through Vite's proxy → 500 → the new guard trips (≈750
  hits across ~all specs — NOT app bugs, a mock-coverage gap). Complete the shared mock reference-data coverage
  for the session-bootstrap forkJoin catalogs (counter-unit-types, aircraft-types, aircraft-states,
  location-types, countries, club-states, …) installed before first nav by every mock-auth spec — cleanest
  robust approach without masking genuine errors. Fold the 3 deliberate cross-tenant-404 opt-outs
  (`allowConsoleErrors(testInfo, /\b404\b/)`: accounting-rules:688, deliveries:300, delivery-creation-test:471).
- [ ] **T-11** — Stub the residual per-screen secondary resolvers still 500ing after T-10 (persons,
  club/member-states, flight-types, flight-crew-types, accounting-rule-filter-types, aircraft-reservation-types,
  locations, aircraft/picker) — re-mine the post-T-10 gate run for the actual residual set; iterate to green.

## Assumptions made

1. **J-2c, not a reopened J-2b** — J-2b is merged (PR #230); this is fresh operator-observed breakage on the same
   screen, so it carves as a sibling hardening journey rather than reopening the closed one.
2. **No migration / no new screen** — the `today..today` default already landed in J-2b; J-2c only repairs the
   filter control's binding, interaction, and styling. If the fix turns out to need a store/API change (e.g. the
   default isn't actually reaching the control because of a store bug, not a binding bug), that stays in this
   journey as another task — not a new story.
3. **Proof in the real-idp lane** so the captures land in the gallery; functionally a mock-IdP run would suffice
   (no auth-role or migration surface), but the gallery convention uses the real-idp proof-video/​screenshot path.
