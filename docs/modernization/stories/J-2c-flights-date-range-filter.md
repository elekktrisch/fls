---
id: J-2c
title: Flights list date-range filter — default visibility + working controls + styling
epic: E-07
status: done
started_at: 2026-06-21
done_at: 2026-06-21
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
parity_test: alpenflight/web/e2e/tests/real-idp/flights-date-filter-parity.spec.ts (real-idp proof — initial / mouse-edit / keyboard-entry — for the gallery; alpenflight/web/e2e/tests/flights/flights-list.spec.ts is the mock inner-loop)
adr_refs: [0024, 0022]
---

## Context

J-2b adopted the legacy `today..today` default range and the list correctly fetches today's flights, but the
date-range filter CONTROL was broken: the today default didn't surface in the inputs, interacting threw console
errors, and the from-field showed a duplicate underline. The daily list is the hot path; this journey makes the
filter work and look right, proven by initial/mouse/keyboard screenshots under a zero-console guard. Mid-journey
the operator asked to roll that console-error guard out suite-wide.

## Spec must assert

1. **Default visible** — `/flights` loads with today in both from/to inputs.
2. **Mouse editing works** — pick from + to → list refetches; zero console errors.
3. **Keyboard entry works** — type dates → list refetches; zero console errors.
4. **Zero console errors** — a `console`/`pageerror` guard fails on the first error (the functional proof the controls work).
5. **Clean underline** — a single underline on focus.

## Decisions (ship-time)

- **Root cause was keyboard entry, not the default.** Today + mouse already worked; typed `dd.MM.yyyy` dates never
  committed — `NZ_DATE_LOCALE` was missing (ng-zorro couldn't deserialize the typed format) + `af-date-picker`
  re-projected the external `[value]` each CD pass, clobbering half-typed input. Fixed via `NZ_DATE_LOCALE`
  (date-fns adapter, per-locale) + a `linkedSignal` working model.
- **Duplicate underline** = ng-zorro's `.ant-picker-active-bar` stacking on the brand border — hidden, scoped to
  `af-date-picker`; real `inputId`→`nzId` label association added. No design reference documents this filter (the
  date-range row is a pragmatic addition); the fix matched the existing `af-*` conventions.
- **The operator's "console errors" symptom was real** — a meta-CSP `frame-ancestors 'none'` (browser-rejected in
  `<meta>`) logged on every page load (pre-dates J-2c, #83). Removed from the meta; real frame protection moves to
  a response header per **S-041** (`[FRAME-ANCESTORS-HEADER]` rider).
- **Suite-wide console guard** (operator request) — a shared `test` fixture asserts no `console.error`/`pageerror`
  per test across all e2e projects, with a conservative benign allowlist + narrow per-test opt-outs for
  deliberate-error paths (403/404/409/412/422). NO genuine app bugs surfaced — the only failures were the
  mock-auth lane's missing reference-data stubs (it runs with no backend), now completed.
- **Mock reference-data stubs are mock-auth (`chromium`) only** — the console WATCH is universal, but the stubs
  must NOT intercept real-idp specs' live `/api/v1/*` calls (they were masking e.g. token-lifecycle's real-401 leg).

## Parity exclusions / scope caps

- The mock-auth stubs return empty/minimal SHAPE-CORRECT bodies, scoped to known paths (no blanket 200); spec
  overrides win. `last-context`/kebab `flights/{id}` no-row → 200-null (a stubbed 404 still emits a browser console error).
- Real frame protection (`frame-ancestors` as a response header) is S-041's, not J-2c's.

## Tasks

- [x] T-01 — Spec stub (3 states + zero-console guard, red-first) + J-2c gallery scaffold.
- [x] T-02 — Per-push heavy lane → J-2c's real-idp proof spec.
- [x] T-03 — af-date-picker keyboard-commit fix (`NZ_DATE_LOCALE` + `linkedSignal`).
- [x] T-04 — Duplicate-underline CSS + real `inputId`/label association.
- [x] T-05 — Full proof assertions + gallery captures + AEROTOW-SELECT-FLAKE rider.
- [x] T-06 — Remove the meta-rejected `frame-ancestors` CSP directive (operator console-error symptom).
- [x] T-07 — Shared no-console-errors guard fixture + J-2c adoption.
- [x] T-08 — Gallery-index probe: context-agnostic (proof + fanout layouts).
- [x] T-09 — Suite-wide guard rollout (56 specs → shared `test`; 15 deliberate-error opt-outs).
- [x] T-10 — Auto-stub the 6 session-bootstrap reference catalogs + 3 cross-tenant-404 opt-outs.
- [x] T-11 — Auto-stub the residual per-screen resolvers (DTO-correct shapes).
- [x] T-12 — `last-context`/`flights/{id}` → 200-null; handshake-404 + reservations-409 opt-outs.
- [x] T-13 — Flip spec-local `last-context` stubs 404→200-null in the flights create/wizard specs.
- [x] T-14 — Scope the mock stubs to the `chromium` project only (watch stays universal) — fixes real-idp masking.

## Outcome

Shipped green on `1ee68521`: real-idp proof (J-2c 4/4 — today-visible, mouse-edit, keyboard-entry, zero-console,
clean underline) + dashboard/profile proofs + all 4 mock-auth shards. The operator's no-console-errors guard now
runs suite-wide (56 specs) and immediately earned its keep by surfacing the latent `frame-ancestors` console error
the memory-starved dev box couldn't reproduce. No migration ⇒ no fanout gate. The per-journey gallery renders the
3 date-filter captures + the pass video.

## Assumptions made

1. **J-2c, not a reopened J-2b** — fresh operator-observed breakage on the merged J-2b screen → sibling hardening journey.
2. **No migration / no new screen** — repairs the filter control's binding, interaction, and styling only.
