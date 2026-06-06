---
id: J-6
title: Planning days + setup
epic: E-08
status: in_progress
started_at: 2026-06-06
journey0: false
carved: true
depends_on: [J-5, J-2]
rolls_up: [S-070, S-071, S-086]
acceptance:
  - "[happy] Club admin creates a planning day (date + location + instructor / tow-pilot / flight-operator + remarks); it appears in the future-days list."
  - "[happy] Edit a planning day's crew assignments; change persists and reflects on reopen."
  - "[happy] The edit screen shows that day's AircraftReservations inline (join to J-5's reservations)."
  - "[happy] Setup wizard at /planningsetup bulk-creates planning days across a date range filtered by weekday (e.g. every Sat+Sun between start/end) at a location; created days appear in the list."
  - "[key-error] Delete a planning day; its assignments cascade-delete; the list no longer shows it."
  - "[edge] Tenant isolation: a planning day created by club A is not readable by club B (cross-tenant GET 404)."
  - "[happy/email] Assigning crew + running PlanningDayNotificationJob sends imminent (tomorrow) + week-ahead reminder emails to the assigned instructors / pilots → lands in mailpit."
  - "[migration/parity] Real legacy→migrate→Keycloak→AlpenFlight: a migrated planning day (with assignments + fan-out-resolved location) renders for the migrated club admin, identity-matched to legacy."
screen: /planning (+ /planning/:id/edit|view, /planningsetup)   # replacing legacy flsweb planning/
headless_pulled_in: PlanningDayNotificationJob (S-086) → real screen — assigning crew + the scheduled mail; asserted via the existing real-idp mailpit-client helper
migration: PlanningDay + PlanningDayAssignment + PlanningDayAssignmentType (legacy PlanningDays / PlanningDayAssignments / PlanningDayAssignmentTypes) — schema V4 + mappers already authored, NOT bound/run
parity_test: alpenflight/web/e2e/tests/real-idp/planning-migration-parity.spec.ts
adr_refs: [0005, 0008, 0009, 0013, 0021, 0024]
---

## Context

Per-day operations setup: a club's planning day fixes who is on duty (flight
instructor, tow pilot, flight operator) at a location for a date, surfaces that
day's aircraft reservations inline, and drives the reminder emails instructors
expect. The setup wizard bulk-creates days for a season (e.g. every weekend
between two dates). This is the first journey to join J-5's `AircraftReservation`
read-side into another screen, and the first to home a scheduled **notification
job** on a real product screen (assign crew → mail goes out). Required before a
tenant goes `active`.

## Spec must assert

The contract the green Playwright run proves, grounded in legacy
`flsweb/src/planning/`:

- **List (`/planning`)** — paged future-days list. Legacy default filter is
  `Day.From = today`, sort `Day asc`, count 100
  (`PlanningDaysController.js:11-21`); page POST is
  `/api/v1/planningdays/page/{start}/{size}` returning `{Items, TotalRows}`
  (`PlanningService.js:8-18`). Saturday/Sunday rows are visually flagged
  (`:40-41`). Actions: new, edit, view, delete (`:53-75`).
- **Edit (`/planning/:id/:mode`)** — fields `Day`, `LocationId`,
  `InstructorPersonId`, `TowingPilotPersonId`, `FlightOperatorPersonId`,
  `Remarks` (edit-form bindings). `:id === 'new'` opens a blank create form with
  `CanUpdateRecord: true` (`PlanningDayEditController.js:40-44`); save POSTs to
  `/api/v1/planningdays` (insert) or `/api/v1/planningdays/:id` with
  `X-HTTP-Method-Override: PUT` (update) (`PlanningService.js:43-83`). `mode`
  gates edit-vs-view (`:49-53`). The edit screen loads + lists that day's
  reservations via `GET /api/v1/aircraftreservations/planningday/:id`
  (`:96-104`) and links each to J-5's reservation editor; "new reservation"
  pre-seeds date + location into J-5's create form (`:128-132`).
- **Setup wizard (`/planningsetup`)** — `StartDate`, `EndDate`, seven
  `Every<Weekday>` checkboxes, `LocationId` (defaults to the club's
  `HomebaseId`), POST `/api/v1/planningdays/create/rule` returns the array of
  created days, then routes back to `/planning`
  (`PlanningDaySetupController.js:8-34`, `planning-setup.html`). This is what the
  legacy `PlanningDaysRuleBased` service is — **the bulk weekday-expansion
  endpoint, not rule-driven crew assignment** (resolves the S-070 open question).
- **Delete** — `X-HTTP-Method-Override: DELETE` on `/api/v1/planningdays/:id`;
  schema cascades assignments (`fk_pda_planning_day_id … ON DELETE CASCADE`, V4).
- **Tenancy** — every query `@TenantId`-scoped on `operating_club_id` (V4);
  cross-tenant read 404s (the J-0/J-1/J-5 pattern).
- **Notification (S-086)** — `PlanningDayNotificationJob` mails tomorrow's
  planning-day status + a 7-day-ahead reminder to assigned crew; two templates
  (imminent + week-ahead). Proven by running the job after assigning crew and
  asserting mailpit receives both (reuse `tests/real-idp/_helpers/mailpit-client.ts`).
  **Exact 7-day window + recipient set: confirm at ship time via `legacy-oracle`**
  (S-086 note — could be per-club via EmailTemplate override S-055).

## Notes

**No design oracle.** There is **no `docs/modernization/design-reference/screens-planning.jsx`**
(ADR-0024 pixel reference). Build to the established design-system tokens and
**reuse J-5's reservations idiom** (`screens-reservations.jsx` set the calendar +
edit-form visual language); the per-day reservation list reuses J-5's reservation
row/card. Call this out at ship time — no pixel oracle means the screen shape is
derived from the legacy structure + the J-5 design language, not a reference jsx.

**Assignment model — the load-bearing shape decision.** Legacy UI surfaces **3
fixed person pickers** (Instructor / TowingPilot / FlightOperator), but the
legacy *data* model + the V4 schema are **generic typed assignments**:
`t_planning_day_assignment` rows keyed by `t_planning_day_assignment_type` (a
per-club reference table with `assignment_type_name` + `required_nr_of_assignments`).
The migration mappers already commit to the generic model
(`PlanningDayAssignmentTypeMapper`, `PlanningDayAssignmentMapper`). **Decision for
/do-ship:** store generic typed assignment rows (parity with schema + migration),
present the 3 well-known role pickers on the form (parity with the legacy UI) by
mapping each picker to its seeded assignment-type. Do not collapse the schema to 3
FK columns — that would diverge from the authored mappers and the legacy data.

**Migration — authored but never run (the J-5/J-0b pattern).** Schema exists
(V4 `t_planning_day` / `t_planning_day_assignment` / `t_planning_day_assignment_type`,
from the S-014 baseline) and the three mappers exist
(`migration-bundle/.../accounting/PlanningDay*Mapper.java`) **with unit tests** —
but there is **no `MapperLegacyBindings` producer entry**, so the producer SELECT
+ real round-trip have never run end-to-end ([[verify_infra_is_run_not_just_authored]]).
This journey wires the bindings and proves the real round-trip — and is the
natural home to *consume* the **mapper-binding contract-check rider** below (it
was filed for exactly this case). PlanningDay is **fan-out NO** (per-club
operational data) but **references Location, which fans out** — so the migrated
day exercises J-0b's `(legacy_guid, club_id)` `ForeignKeyResolver` resolution to
the referencer's own-club Location replica (good migrate-fidelity coverage; assert
the migrated day points at *its own club's* location copy).

**Date-range invariant** lives on the `PlanningDay` constructor (per the V4
comment removing `ck_pln_planning_date_reasonable` under ADR-0022 directive 2), not
the DB — a domain method, not a CHECK.

### Riders to fold in (from `_BOYSCOUT.md` — `/do-ship` sizes + clears)

Directly on this journey's surface (the ≥60% feature carries these in its build):
- **Low-CRAP edit page — reuse, don't replicate.** Build the planning-edit page on
  J-5's *extracted* shared form↔request + `errorPatch` helper (the maintainability
  rider); do **not** re-grow the `formToUpdateRequest` / `finalSubmit` / `errorPatch`
  complexity the other `*-edit.page.ts` carry. *(seam: shared form-mapping helper)*
- **Early mapper-binding contract check** for migration journeys — fail fast at
  build time if a carried mapper has no `MapperLegacyBindings` entry / its producer
  SELECT names a dropped column, before the ~20-min fanout. J-6 both needs it (it
  wires PlanningDay bindings) and proves it. *(seam: migration-tool
  MapperLegacyBindings contract test)* [[verify_infra_is_run_not_just_authored]]
- **orval `operationId` stability** — set explicit `operationId`s on the new
  planningday endpoints (positional `getN` naming is fragile across regen).
  *(seam: backend operationId annotations + orval config)*

≤40% tech-debt budget (gallery re-arch is split across the next 2-3 journeys — J-6
takes a slice; `/do-ship` picks which, sized to its gate):
- **Per-journey gallery page + Maintainability panel** continuation (generator
  keys-by-journey; FE fallow + BE PMD/CPD delta panel). [[feedback_proof_gallery_per_journey_one_bookmark]]
- **Scope the per-push `alpenflight-mock-e2e` gate to the journey-under-work**
  (the mock-e2e half of what J-5 T-14 did for real-idp). [[feedback_dev_time_test_strategy]]
- **CI fail-aggregate** (parallel reporting jobs) + **assert gallery shots PRESENT**
  guard — general CI/fanout hygiene, ride if the gate budget allows.

### Likely task seams (non-binding, seam-granularity for `/do-ship`)

- `PlanningDay` aggregate in `ch.alpenflight.planning` — child `PlanningDayAssignment`
  + `PlanningDayAssignmentType` lookup; crew-assignment + date-range invariant methods.
- `JpaPlanningDayRepository` — paged future-days query + per-day reservations join.
- PlanningDay CRUD resource — DTOs / service / mapper / controllers (page,
  overview/future, GET :id, insert, PUT-override, DELETE-override).
- Bulk rule-based creation endpoint (`/planningdays/create/rule`) — weekday-expansion
  over a date range.
- `PlanningDayNotificationJob` (S-086) + 2 email templates (imminent + week-ahead) —
  ADR-0009 job mechanism, ADR-0013 email.
- SPA: planning **list** page + store + route + orval client; planning **edit** page
  (reuse J-5 form helpers); **setup wizard** (multi-step state machine in the store).
- Migration: wire `MapperLegacyBindings` for the 3 PlanningDay mappers + producer
  SELECT; prove the real round-trip (binding-presence check rider above).

### Assumptions made

1. One journey, one route family (`/planning` + `/planningsetup`) — the setup
   wizard is a second view on the same feature, not its own journey (roadmap A5).
2. `PlanningDaysRuleBased` = the setup wizard's bulk weekday-expand endpoint, not
   rule-driven crew assignment (resolves the S-070 investigate-note from code).
3. Generic typed-assignment storage with 3 fixed role pickers on the form (above) —
   not 3 FK columns; keeps parity with the authored mappers + legacy data.
4. S-086's exact 7-day window + recipient rules are deferred to a ship-time
   `legacy-oracle` read (carve captures shape; oracle captures exact behavior).
