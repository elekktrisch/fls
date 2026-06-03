---
id: S-062d
title: Flight list mobile-first, sticky filter, offline, saved-recents
epic: E-07
status: todo
rolled_up_into: J-2
depends_on: [S-062b, S-067]
acceptance:
  - **AC-DIR-1 (mobile-first card layout).** At `<md` (< 768 px) the list renders as a stack of cards (one card per flight) showing the legacy-equivalent of "immat + pilot + start/landing times + state badge + actions menu". The `<af-data-table>` primitive (S-008) supports a "card mode" at `<md` and reverts to a row-table at `≥md`. Same data, breakpoint-driven layout.
  - **AC-DIR-2 (dense-desktop variant).** At `≥lg`, the table renders with denser row padding + a compact filter bar (inline labels, smaller spacing). Same component, CSS-driven density. The legacy "filter bar takes 30% of vertical real estate" pattern is not carried forward.
  - **AC-DIR-3 (sticky filter + sticky pagination).** Filter bar sticks to the top of the viewport (under the nav-bar); pagination sticks to the bottom. On mobile, both collapse to a floating action button that expands a sheet.
  - **AC-DIR-4 (touch targets on row actions).** Row actions (Edit / Copy / Delete) meet ≥ 44 × 44 CSS px on `<md`; on dense desktop ≥ 28 × 28 px for icon-only buttons.
  - **AC-DIR-5 (keyboard-first dense navigation).** On `≥lg`: Tab/Shift+Tab walks rows; Enter = open edit on focused row; `n` = new flight; `/` = focus filter; arrow keys move row focus. Playwright spec asserts core navigation completes without mouse.
  - **AC-DIR-6 (offline-aware list).** When offline, the list renders the last-cached page from IndexedDB (refresh on reconnect). A subtle "offline — last refreshed at HH:mm" banner sits under the filter. Consumes the PWA service worker (C17 / ADR 0014). The list must not present a blank state when offline if cached data exists.
  - **AC-DIR-7 (mobile-class connectivity).** At simulated 200 ms RTT + intermittent loss: cached list renders immediately; refetch is non-blocking; no spinner > 3 s.
  - **AC-DIR-8 (saved-filter recency).** "Recently used filter combos" surface as one-tap chips above the filter bar (last 5 distinct combos per user). Generalizes the per-field-recency pattern from S-062c AC-DIR-6 to the filter bar.
estimate: M
adr_refs: [0006, 0014, 0024]
split_from: S-062b
origin: amendment-2026-05-15b
refined: false
---

## Context

Spun out of S-062b at implement-time. The 2026-05-15b vision amendment (see [`02-vision-and-constraints.md`](../02-vision-and-constraints.md) §C21–C24) designates the flight list as **the** airfield hot-path screen alongside flight-edit (S-062c) and adds 8 layered ACs covering mobile-first rendering, sticky chrome, offline cache, and saved-filter recency.

S-062b shipped the baseline desktop card layout (visual reference: `docs/modernization/design-reference/screens-logbook.jsx` + screenshots `02-desktop-cards.png`), the FlightStore over the keyset-cursor endpoint, and the navigation entry points. The AC-DIR set roughly doubles the original M estimate (offline cache + IndexedDB + service worker hookup + sticky chrome + density variants + saved-filter chip primitive), so the operator chose to spin it out rather than re-refine S-062b mid-implement.

## Out of scope

- Anything S-062b already shipped (baseline list, FlightStore, navigation, server date-range, client AirState/AircraftType narrowing).
- Backend filter reshape (`POST /flights/search` for substring + multi-select server filters) — separate follow-up.
- Form-driven flows (still S-062c).

## Pickup notes

Refinement should re-derive design + test + performance plans from the amendment block, not from the original S-062b refinement (which predates the amendment). Inputs:
- S-067 (offline / PWA / service worker) — confirm landing before this story enters `in_progress`.
- S-008 (`<af-data-table>` card-mode variant) — may need a primitive-side change.
- S-006 (Signal Store + MUTATION_BUS) — the offline-aware refetch hook plugs in here.
