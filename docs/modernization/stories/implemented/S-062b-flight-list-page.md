---
id: S-062b
title: Flight list page (paginated, filterable)
epic: E-07
status: done
started_at: 2026-05-25
done_at: 2026-05-25
depends_on: [S-062a, S-006, S-008]
acceptance:
  - `/flights` route renders a list of flights backed by `GET /api/v1/flights` (keyset cursor — `from / to / after / limit`).
  - Filter bar: date range (server-side `from`/`to`), AirState chip and AircraftType chip (client-side narrowing over the loaded page until `/flights/search` lands).
  - `FlightStore` (NgRx Signal Store) wraps list state; refetch on route entry, on `MUTATION_BUS` `flight.booked`, and on `session.tenantSwitch` (wipe-then-reload); clear on `session.logout`.
  - "New flight" navigates to `/flights/new`; row click + kebab → Edit navigate to `/flights/:id/edit`; kebab → Copy navigates to `/flights/copy/:id`. The three mutate routes render a placeholder page until S-062c lands.
  - List row shows date (`dd.MM.yyyy`), aircraft immatriculation, aircraft-type pill, AirState pill, ProcessState pill, takeoff, landing, duration. Pilot / second-crew / location / comment / tow columns deferred per S-062a "decorations deferred".
  - `data-testid` contract on every interactive element (handoff to S-109).
estimate: M
adr_refs: [0005, 0008]
parity_test: alpenflight/web/e2e/tests/flights/flights-list.spec.ts
refined: true
refined_at: 2026-05-14
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
split_from: S-062
github_issue: 122
github_pr: 123
---

## Context

Second of three sub-stories splitting the original S-062 (after S-062a backend). Builds the SPA logbook on top of the keyset-cursor GET shipped in S-062a; placeholders for create/edit/copy hand off to S-062c.

## Load-bearing decisions

- **Reconciled to S-062a's shipped surface.** The 2026-05-14 refinement assumed a `POST /flights/search` endpoint with rich server-side filters (immat substring, pilot name, AirState dropdown, ProcessState dropdown, FlightType, StartLocation) and a `TableSettingsCache` per-user persistence shape. S-062a explicitly deferred that reshape (see "Out of scope (NOT deferred — known gaps)" in `implemented/S-062a-flight-crud-backend.md`). This story builds the realistic logbook on `GET /api/v1/flights` (date range only server-side) and narrows AirState / AircraftType client-side over the loaded page. Substring filters, ProcessState filter, and saved-filter persistence wait on the `/flights/search` story.
- **Visual + content split.** Visual aesthetic = design-reference logbook prototype (`docs/modernization/design-reference/screens-logbook.jsx` + screenshots `02-desktop-cards.png`) — card-per-flight, status pills with tone dots, slate borders, tabular numerics. Column content = legacy `flsweb/src/flights/flights.html` (date `dd.MM.yyyy`, immat, takeoff `HH:mm`, landing `HH:mm`, duration, AirState, ProcessState). Columns we do not yet ship: PilotName, SecondCrewName, StartLocation, FlightComment, TowAircraftImmatriculation, TowPilotName, TowFlightLdgDateTime, TowFlightDuration — all of these need a decoration pass on `FlightListItem`.
- **`processState` decoration added to `FlightListItem` in this PR.** The endpoint shipped only `processStateId` (UUID); the SPA cannot render a label from that alone. Server mapper now derives `processState: FlightProcessState` via `FlightProcessState.fromId(processStateId)` and the DTO carries both. Operator-confirmed scope expansion.
- **Aircraft immat resolved via `AircraftStore` (cross-feature import).** Acknowledged §10 violation; eliminated when `FlightListItem` is server-decorated with `aircraftImmatriculation`. Tracked alongside the rest of the deferred decorations.
- **No `TableSettingsCache` / `localStorage` persistence.** Forbidden in app code per CLAUDE.md §10 (auth-only allowlist). Pending a session-scoped settings primitive.
- **No client mutate guards.** Legacy shows "New flight" + row actions to all authenticated users; the server is the authz authority. `tenantRequiredGuard` on every route gates sysadmin (no tenant) to `/clubs`.

## Out of scope (deferred to follow-up stories)

- **S-062c** — Edit / Create / Copy forms (replaces the placeholder routes).
- **S-062d** (filed in this PR, `origin: amendment-2026-05-15b`) — mobile-first card layout, sticky filter + pagination, offline-aware list (IndexedDB), saved-filter recents, keyboard-first dense navigation.
- **`/flights/search` (no story yet)** — POST-shaped search endpoint that enables server-side substring/process-state/multi-select filters + URL-shareable filter state.
- **List decorations on the server** — extend `FlightListItem` with `aircraftImmatriculation`, `pilotName`, `secondCrewName`, `startLocation`, `flightComment`, plus the four `tow*` fields. Removes the cross-feature import + closes the column-content gap.
- **k6 load + Fast-3G LCP measurement** — performance verification per S-108/S-111.

## Notes for the next implementer

- The reviewer panel called out additional improvements deferred to follow-ups: extract date utilities (`formatLegacyDate` / `formatTime` / `durationBlock`) to `shared/util/date/` when a third consumer surfaces; consolidate state-pill tone/label/dot helpers into one `<af-state-pill>` molecule when the third pill ships (the current page has two); migrate hardcoded English labels to Transloco keys when S-005/S-057 mature.
- `openapi/openapi.json` was hand-edited in this PR (added `processState` to `FlightListItem`); `OpenApiSnapshotIT` will fail in CI if the live snapshot disagrees.
