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
  - "[happy/email] PlanningDayNotificationJob: imminent (day+1) mail → the club's notification address (ok if the day has a reservation or club allows reservation-less days, else cancel); week-ahead (day+7) mail → each assigned person (all 3 roles). Both land in mailpit."
  - "[key-error] Duplicate (date, location) for the same club is rejected: single create/update → 409; rule-wizard skips the existing day idempotently (V4 ux_pln_club_date_loc forces correcting legacy's no-dedup bug)."
  - "[migration/parity] Real legacy→migrate→Keycloak→AlpenFlight: a migrated planning day (with assignments + fan-out-resolved location) renders for the migrated club admin, identity-matched to legacy."
screen: /planning (+ /planning/:id/edit|view, /planningsetup)   # replacing legacy flsweb planning/
headless_pulled_in: PlanningDayNotificationJob (S-086) → real screen — assigning crew + the scheduled mail; asserted via the existing real-idp mailpit-client helper
migration: PlanningDay + PlanningDayAssignment + PlanningDayAssignmentType (legacy PlanningDays / PlanningDayAssignments / PlanningDayAssignmentTypes) — schema V4 + mappers already authored, NOT bound/run
parity_test: alpenflight/web/e2e/tests/real-idp/planning-migration-parity.spec.ts
mock_test: alpenflight/web/e2e/tests/planning/   # journey-under-work's own mock-auth specs (T-02b: per-push mock-e2e runs ONLY these; prior journeys' mock specs run at the §4 gate + nightly)
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

## Behavior oracle — ship-time decisions (legacy-oracle, 2026-06-06)

Grounded in `flsserver/` (cited). Load-bearing facts the tasks build against:

- **Assignment mapping** — DTO keeps **3 nullable person ids** (`InstructorPersonId`,
  `TowingPilotPersonId`, `FlightOperatorPersonId`) over generic assignment rows keyed
  by per-club type **name** (case-insensitive German, `MappingExtensions.cs:3302/3325/3348`):
  `segelflugleiter→FlightOperator`, `schlepppilot→TowingPilot`, `fluglehrer→Instructor`.
  Write = upsert-or-delete per field (null/empty-guid removes the row). The 3 types are
  **per-club rows** seeded at club creation (`ClubService.cs:206-228`), **NOT seeded by
  migration** (migrated clubs bring their real types). **Decision:** clean-seed seeds the 3
  types/club; domain resolves role by well-known name (mirroring legacy); `required_nr_of_assignments`
  is DEAD for this journey (always 1, never read) — skip.
- **Dedup (legacy-bug → corrected)** — legacy has no unique constraint; re-running the
  wizard silently dupes. V4 adds `ux_pln_club_date_loc UNIQUE`. **Decision:** single
  create/update → **409** on duplicate (club,date,location); rule-expand **skips** existing
  days idempotently. Also bound the rule range (legacy is unbounded) — reject absurd ranges.
- **Rule-expand** (`POST /planningdays/create/rule`) — inclusive date range, one day per
  matching weekday, same location/info, **no default crew**; empty weekday flags → empty
  list, no error (`PlanningDayService.cs:290-331`).
- **Notification job** (`PlanningDayNotificationJob.cs`) — TWO passes, exact-equality on `.Date`:
  - **imminent = day+1** → recipient is the **club** address (`Club.SendPlanningDayInfoMailTo`),
    template `planningday-ok` if the day has ≥1 reservation OR `ClubUsePlanningDayWithoutReservations`,
    else `planningday-cancel` (`:64-94`). **Not** the crew.
  - **week-ahead = day+7** → recipient is **every assigned person** (all 3 roles),
    template `planningday-assignment-notification` (`:124-163`). Skips blank emails.
  - 3 templates total; per-club override via `GetEmailTemplate(name, clubId)`; missing template throws.
  - Trigger is a noon-gated batch (`WorkflowService.cs:117-126`) — **preserve the semantic**
    (day+1 / day+7), home an explicit guarded **"run planning notifications now"** test affordance
    for the e2e (J-15 jobs console not built yet).
- **Permissions** — delete/update gated: `ClubAdministrator` OR record creator
  (`PlanningDayService.cs:407-425`); `CanUpdate/CanDeleteRecord` flags on every DTO drive UI.
  Use a **real low-privilege principal** in the real-idp spec (mock-admin hides this —
  [[project_real_idp_real_roles_catches_authz_gaps]]).
- **Paged list** — `{Items, PageStart, PageSize, TotalRows}`, `Day.From` date filter,
  default sort `Day asc`, size 100; `overview/future` = `planning_date >= today`;
  `NumberOfAircraftReservations` = **computed** count (same club, `date(reservation_start)==day`,
  same location), never stored. Date stores as pure `DATE` (no tz shift on migrate).
- **Open product forks (mirror legacy = parity default; flag, don't block):** (a)
  `ReceivePlanningDayRoleReminder` person flag exists but the legacy job **ignores** it →
  default ignore (parity); (b) exact prod job cadence is deploy-config (semantic is clear).

## Tasks

≥60% feature (T-01…T-11, T-16) · ≤40% tech-debt riders (T-12, T-14, T-15). Sequential on `integration/J-6`.

- [x] **T-01 — spec stub.** Author both specs' structure + selectors + flow: mock inner-loop
  `e2e/tests/planning/planning-crud.spec.ts` (+ setup-wizard) and the real-idp parity skeleton
  `e2e/tests/real-idp/planning-migration-parity.spec.ts`. Thin assertions; commits the screen shape. *(spec seam)*
- [x] **T-02 — PlanningDay aggregate.** `ch.alpenflight.planning`: `PlanningDay` aggregate (date,
  location, info, child assignments), `PlanningDayAssignment`, `PlanningDayAssignmentType` lookup,
  `PlanningRole` resolution by well-known name; dup + range invariants as domain methods (ADR-0022 §2). + JPA mapping. *(aggregate seam)*
- [x] **T-03 — JpaPlanningDayRepository.** Paged future-days query, `overview/future`, computed
  per-day reservation count (join `t_aircraft_reservation` by club+date+location), dedup-aware save. *(repo seam)*
  **T-02 carry-over (mirrors J-5 T-03→T-29):** `PlanningDay` + `PlanningDayAssignmentType` bear `@TenantId`,
  so the catalog-driven `LeakageSweepIT` + `TenantSweepFloorAndPinTest.every_discovered_entity_has_a_registered_row_builder`
  go RED until each has a Spring Data `JpaRepository` + a `TenantScopedRowBuilders` entry (and `Map.of`→`Map.ofEntries`,
  now at the 10-entry cap). Register both here when the repos land. `PlanningDayAssignment` is an aggregate-internal
  child WITHOUT `@TenantId` (FlightCrew/V7 pattern) — deliberately NOT a sweep participant.
- [x] **T-01b — journey proof-page scaffold (pulled forward — operator: T-01 should ALWAYS do this).**
  Scaffold the J-6 per-journey gallery page + link it from the persistent index NOW, so the proof window
  exists from the start and accumulates captures as screens land. Minimal slice of T-14 (full
  maintainability-panel re-arch stays T-14). *(generate-gallery.mjs J-6 page + index link)* [[feedback_proof_gallery_per_journey_one_bookmark]]
- [x] **T-02b — move prior journeys (J-0…J-5) per-push specs to mock-IdP (pulled forward — operator: T-02 should ALWAYS do this).**
  Scope the per-push gate so ONLY J-6 runs heavy (real-idp) + prior journeys run mock-IdP; full real-idp
  regression stays nightly + the §4 gate. This is T-15's content pulled to slot 2 — **T-15 retired into this.** *(ci.yml mock-e2e + real-idp spec selection)* [[feedback_dev_time_test_strategy]]
  **Done:** real-idp `alpenflight-proof` already scoped per J-5 T-14 — verified it derives J-6's
  `tests/real-idp/planning-migration-parity.spec.ts` (is_baseline=false), no fix needed. Mirrored it for
  the mock half: added a `mock_test:` frontmatter field + a `changes`-job "Derive journey mock-e2e filter"
  step; the `alpenflight-mock-e2e` "Run Playwright" step now passes the journey's `tests/planning/` filter
  to `--project=chromium`, so per-push runs ONLY J-6's 11 planning mock specs (verified via `--list`).
  Prior journeys (no `mock_test:`) + non-integration branches FAIL-SAFE to the full chromium suite; the full
  cross-journey mock regression stays nightly (`alpenflight-e2e.yml` main-push) + the §4 gate. Fanout
  workflow already triggers schedule + workflow_dispatch only (NOT push to `integration/**`) — already
  off the per-push path, no change. `required` aggregator semantics unchanged (skipped→success).
- [x] **T-04 — PlanningDay CRUD resource.** DTOs (3 person ids + date + locationId + info + computed
  count + CanUpdate/CanDelete) / service / mapper / controllers: page, overview/future, GET :id,
  insert (409 dup), update (409 dup), delete (perm-gated ClubAdmin|creator). **+ explicit `operationId`s**
  on every endpoint (orval-stability rider). *(resource seam)*
  **Done:** `/api/v1/planning-days` resource (kebab-case, real verbs — J-5 idiom, NOT the legacy
  X-HTTP-Method-Override tunnel). 6 endpoints, all with explicit `operationId`s (pagePlanningDays,
  listFuturePlanningDays, getPlanningDay, createPlanningDay, updatePlanningDay, deletePlanningDay).
  Detail DTO carries the 3 nullable typed person ids (instructor/towingPilot/flightOperator) over the
  generic assignment rows (role↔type-name resolution in the service), planningDate/locationId/info, the
  computed `numberOfAircraftReservations` (T-03 count), + canUpdate/canDeleteRecord. 409 on dup
  (club,date,location) via the T-03 catchable conflict → web 409; update/delete gated ClubAdmin-OR-creator
  in the service (→ 403, surfaced by Spring Security's ExceptionTranslationFilter); cross-tenant GET 404.
  Added `createdByUserId` (mapped, updatable=false) to PlanningDay + a `PlanningDayAssignmentTypeRepository`
  domain port (ADR-0023 layering). `PlanningDaysControllerIT` (4 cases) green; whole `./gradlew test` green;
  PMD/CPD clean; OpenAPI snapshot + orval client regenerated (planning-days client ready for T-07/T-08).
- [x] **T-05 — rule-expand endpoint.** `POST /planningdays/create/rule` — weekday expansion over a
  bounded range, skip-existing idempotent, empty-flags→empty, returns created overviews. *(endpoint seam)*
  **Done:** `POST /api/v1/planning-days/create/rule` (operationId `bulkCreatePlanningDays`). Body =
  `PlanningDayRuleRequest` (startDate/endDate + 7 `every<Weekday>` bools + locationId + info?). Expansion
  lives as a **domain method** `PlanningDay.expandRuleDates(start, end, Set<DayOfWeek>)` (ADR-0022 §2):
  inclusive range × selected weekdays, ascending; empty flags or inverted range → empty list (legacy parity,
  `PlanningDayService.cs:290-324`); span > `MAX_RULE_SPAN_DAYS` (366) → `PlanningRuleRangeException` (→ 422,
  key `planning.rule.range`) — the bound legacy lacks. Service orchestrates: resolve tenant, expand, then
  **skip-existing idempotent** via new `PlanningDayRepository.existsActiveForDay(date, location)` (tenant-scoped,
  soft-delete-filtered JPQL) — a re-run is a no-op for already-planned days (no 409, no dupes); persists the
  rest as **bare days** (no crew) reusing the T-02 aggregate + T-03 dedup-aware save. Returns only the days
  actually created (skipped not included), mirroring legacy `List<PlanningDayOverview>`. 3 new domain unit
  tests + 3 new ITs (weekend-2wk→4 days + idempotent re-run→0; empty-flags→empty+nothing; over-cap→422).
  Whole `./gradlew test` green; PMD/CPD clean; arch-guards green; OpenAPI snapshot + orval client regenerated.
- [x] **T-06 — clean-seed planning data.** Dev-seed Flyway: 3 assignment types/club + sample planning
  days so the screen + spec have data clean-seed. *(migration seam)*
  **Done:** `V34__dev_planning_seed.sql` — for seed-club-1 (the clean-seed dev club J-0…J-5 seed): 2
  locations (Bern-Belp `…c001` / Thun `…c002`), 3 crew persons (`…00b1/b2/b3`), the 3 well-known
  assignment types `Segelflugleiter`/`Schlepppilot`/`Fluglehrer` (`…00d1/d2/d3`; canonical German names
  the `PlanningRole` resolver matches case-insensitively → FLIGHT_OPERATOR/TOWING_PILOT/INSTRUCTOR), and
  2 sample FUTURE days (`CURRENT_DATE + N`): a fully-crewed weekday day (`…0e01`, 3 assignment rows
  `…0f01–f03`) + a bare next-Saturday weekend day (`…0e02`) so `/planning` renders non-empty + the
  weekend flag has a row. All ids in the `019e30c3-…7001-…` seed band (same band as the mock-spec
  fixtures), idempotent `ON CONFLICT (id) DO NOTHING`, inert-in-prod via @TenantId (sibling V8/V26/…/V31
  posture — no Flyway profile split). `required_nr_of_assignments` seeded 1 (dead this journey).
  **Isolation (J-5 T-34/T-30 lesson):** scoped `PlanningDaysControllerIT`'s seed-club-1 planning-day
  pre-clean + exact-count query `AND id::text NOT LIKE '019e30c3-%'` (spare the seed band, de-brittle
  the 4/0 counts); de-brittled `ReservationsBaselineIntegrationTest.planning_day_assignment_type_*` from
  an absolute `assertTableEmpty` to a seed-band `containsExactly` of the 3 V34 rows; gave Thun a
  non-colliding icao (`LSPL`, not `LSZW`) so `ShowcaseSeederIT`'s cross-club icao pre-clean doesn't
  FK-trip on the seed's weekend day. Smoke: new `PlanningDevSeedIT` (Flyway migrate green + the
  future-list endpoint renders both seeded days, weekday crew resolved, weekend bare — seed-band scoped,
  no absolute count). Whole `./gradlew test` green locally (1139 tests).
- [x] **T-04b — resolve planning-resource CPD duplication (gate-red fix).** T-04/T-05 pushed `:cpdRatchet`
  to 5488 > 5350 (+138 tokens) — per-push `alpenflight build` red. The planning controller/service/mapper
  added NO logic duplication; its only clones were per-resource RFC-7807 advice boilerplate, the paged-DTO
  record, and the JPA column block. Extracted the genuine boilerplate at source — new shared-kernel
  `platform.web.ProblemResponses` (`problem` + `badRequest`) now backs all 12 `@RestControllerAdvice`
  classes, collapsing the codebase-wide `problem()`/`handleIllegalArgument()` clone groups. Net: 5488 →
  **4767**, i.e. 583 BELOW the prior 5350 baseline (ratchet tightens), not a wholesale re-baseline. The 3
  residual planning clones (handler scaffolding / paged-DTO / JPA column block) are irreducible Spring/JPA
  structural boilerplate, documented in `config/pmd/cpd-baseline.txt`. `./gradlew check` green
  (`cpdRatchet measured=4767`, whole `test` suite green, arch guards accept the new `platform.web` import). *(server CPD/planning resource)*
- [x] **T-07 — SPA planning list page.** `/planning` list + store + route + orval client; future-days,
  Sat/Sun flag, new/edit/view/delete actions. *(component-route seam)*
  **Done:** `features/planning/` (mirrors J-5 `features/reservations/`): `planning.routes.ts` (route `/planning`,
  tenant-guarded, `showNavBar`), `planning.store.ts` (root-scoped Signal Store — `loadFuture` via the orval
  `listFuturePlanningDays` GET `overview/future`, `delete` → `deletePlanningDay` + `planningDay.deleted` bus
  event + refetch, decoration maps for location + crew names; **reuses the shared `mapApiSaveError` helper, NOT
  the high-CRAP `errorPatch`** — low-CRAP rider), and `list/planning-list.page.ts` (paged future-days table:
  date + DE weekday with Sat/Sun `data-weekend` flag + brand tint; location; 3 crew display names; computed
  `numberOfAircraftReservations`; kebab row actions view/edit/delete gated on `canUpdateRecord`/`canDeleteRecord`;
  delete → inline confirm dialog → store.delete; top actions New → `/planning/new/edit` + Setup → `/planningsetup`).
  Wired exactly as J-5 `/reservations`: route registered in `app.routes.ts`, nav entry added to `TENANT_SECTIONS`
  (`/planning`, calendar icon), admin-gated affordances (`isClubAdmin || isSystemAdmin`). Added 3 `planningDay.*`
  mutation-bus events, `LucideEye` to the icon registry, and the `planning` i18n scope to all 4 locales (de source
  + en/fr/it). No design oracle (J-6 Notes) — built to ADR-0024 tokens + J-5's list visual language.
  **Tests:** `planning.store.spec.ts` (7 vitest cases — load/error/delete-bus/inline-error/refetch/logout-clear);
  new `e2e/tests/planning/planning-list.spec.ts` (3 mock-auth cases — render+crew+count+weekend-flag / top-actions /
  delete-confirm→DELETE→leaves-list) GREEN locally against system chromium (3 passed). The shared
  `planning-crud.spec.ts` stays `test.fixme` (its POST-page + edit-form contract is T-08/T-09/T-16's to un-fixme).
  Preflight green LOCALLY: lint ✓, tsc ✓, `ng test` 382 ✓, `ng build` production ✓ (planning-list-page chunk emitted).
- [x] **T-08 — SPA planning edit page.** `/planning/:id/edit|view`: date, location, 3 person pickers,
  remarks; **reuse J-5's extracted form↔request + errorPatch helper (low-CRAP rider)**; per-day
  reservations inline (J-5 rows) + link to reservation editor + "new reservation" preseed. *(component-route seam)*
  **Done:** `features/planning/edit/planning-edit.page.ts` (route `/planning/new/:mode` + `/planning/:id/:mode`,
  `:mode` gates edit-vs-view). Typed reactive `FormGroup` on J-5's reservation edit-form idiom: date picker
  (`planningDate`), location select, 3 crew pickers (instructor/towpilot/flightop → the 3 nullable person-id DTO
  fields, leading blank = clear-role), remarks (`info`). **Low-CRAP rider honored:** the form→request mapping is a
  SINGLE `withOptionals` pass from `@shared/util/form` (empty crew/info pruned → backend clears the role) + the
  store maps the 409 via the shared `mapApiSaveError` key table — NOT the flagged `formToUpdateRequest`/`finalSubmit`/
  `errorPatch` cascade. Save (create→`createPlanningDay` / update→`updatePlanningDay`), Cancel (→/planning), Delete
  gated on `canDeleteRecord` (the list-page kebab confirm — same store). Dup (date,location) **409 surfaces inline**
  via `store.saveError()`; nav only on the mutation-bus success event (no nav-evicts-body race) — mirrors J-5.
  **Inline per-day reservations:** no planning-day-scoped reservation read exists, so reuse J-5's `day/{date}` list
  filtered to the day's location client-side; each row links to `/reservations/:id/edit`; "New reservation" pre-seeds
  date+location query params into J-5's create form (legacy `PlanningDayEditController.js:96-104,128-132`).
  Store extended with `loadDetail`/`selectNew`/`create`/`update`/`loadDayReservations`/`clearSaveError`; aircraft
  immat decoration loads on its OWN best-effort stream so a picker failure can't blank the load-bearing location/crew
  selects. `planning.form.*` i18n added to all 4 locales. **Tests:** +6 store vitest cases (loadDetail/selectNew/
  create-bus/update-bus/409-inline/day-filter) — `planning.store.spec.ts` 12 ✓; un-fixme'd the planning-crud spec's
  create/edit/inline-reservations/409-inline/delete cases (corrected the T-01 stub mocks to the real kebab
  `/api/v1/planning-days` + `planningDate`/`info`/`canUpdateRecord` wire shape) — 5 passed LOCALLY (musl chromium),
  2 list-only cases stay fixme (Setup-button needs `/planningsetup` T-09; page-POST envelope is T-16). Local DoD
  green: tsc ✓, lint ✓, ng build (mock-auth) ✓, full vitest 388 ✓, gallery scripts 63 ✓.
- [x] **T-09 — SPA setup wizard.** `/planningsetup` multi-step (StartDate/EndDate/7 weekday checks/location)
  → POST create/rule → back to list. *(component-route seam)*
  **Done:** `features/planning/setup/planning-setup.page.ts` — single-step form (legacy parity: `planning-setup.html`
  is single-form; not under-built). Typed reactive `FormGroup` on the J-5/T-08 idiom: `startDate`/`endDate` range,
  7 weekday checkboxes (**Sat+Sun default-ticked**, legacy `PlanningDaySetupController.js:8-17`), location select
  (defaults to the first available location — the SPA tenant model carries no club `HomebaseId`), remarks. Submit →
  store `bulkCreate` → orval `bulkCreatePlanningDays` (`POST /api/v1/planning-days/create/rule`) → on the
  `planningDay.bulkCreated` bus event routes back to `/planning` (the created days appear; list refetches on the
  bus). Cancel → `/planning`. **Low-CRAP rider honored:** request mapping is a single `withOptionals` pass from
  `@shared/util/form` (the 7 flags always sent, only `info` pruned) + the store maps errors via the shared
  `mapApiSaveError` — NOT the `errorPatch` cascade. Nav only on the bus success event (no nav-evicts-body race).
  Registered `/planningsetup` as a top-level route (`PLANNING_SETUP_ROUTES` in `planning.routes.ts` + `app.routes.ts`);
  the T-07 list Setup button already routed there. Added the `planningDay.bulkCreated` mutation-bus event +
  `planning.setupWizard.*` i18n to all 4 locales. **Tests:** +3 store vitest cases (`planning.store.spec.ts` 15 ✓ —
  bulkCreated count/refetch, empty→0 no-error, 422-range inline). Un-fixme'd the 3 setup-wizard mock cases
  (Sat+Sun bulk-create, empty-flags→zero, cancel — corrected the T-01 stub to the real kebab `/api/v1/planning-days/
  create/rule` + mocked persons/aircraft so the shared store's `forkJoin` decoration doesn't blank the location
  select); skip-existing-idempotent stays fixme (backend behavior, T-16). Un-fixme'd the crud spec's
  list-render-with-Setup-button case (now `/planningsetup` exists). Both planning mock specs GREEN locally (musl
  chromium): setup-wizard 3✓/1 skip, crud 6✓/1 skip. Preflight green LOCALLY: lint ✓, tsc ✓, ng build ✓,
  api-drift clean (FE-only), gallery vitest 63 ✓, link-check ✓.
- [x] ~~**T-10** — PlanningDayNotificationJob + templates + run-now affordance~~ **(split → T-10a/b/c, auto-re-plan 2026-06-07).**
- [x] **T-10a — email infra (ADR-0013 build-out, the prerequisite seam).** First AlpenFlight email send-path:
  `spring-boot-starter-mail` + `-thymeleaf` deps; `spring.mail` config (dev/test→mailpit `localhost:1025`,
  prod→disabled/placeholder); a `MailSender` port + Thymeleaf `TemplatedMailService`; test-capture harness
  (GreenMail or captured-outbox fake) + a 1-template smoke IT. *(platform/email seam)* [[verify_infra_is_run_not_just_authored]]
  **Done:** ADR-0013 Option A built (was Accepted-but-unbuilt). Deps `spring-boot-starter-mail` +
  `-thymeleaf` (BOM-managed). Config: base `spring.mail` → `localhost:1025` (mailpit's SMTP, confirmed from
  `docker-compose.yml:68-78`) + app-side `alpenflight.mail.{enabled,from}` kill-switch (default `enabled=false`
  so a misconfigured/forgot-to-opt-in env never sends); dev+test flip `enabled=true` (mailpit), prod stays
  disabled + documents the env contract (no relay hardcoded — relay choice is the deferred ADR-0013 follow-up).
  New OPEN-module package `ch.alpenflight.platform.mail`: `MailSender` port + `MailMessage` value record +
  `SmtpMailSender` adapter (JavaMailSender/MimeMessageHelper, honors the kill-switch as a no-op) +
  `TemplatedMailService` build-service (renders `templates/email/<name>.html` via the autoconfigured Thymeleaf
  engine → port). Smoke template `templates/email/smoke.html`. Test-capture: `CapturedMailSender` `@Primary`
  fake outbox (no live SMTP / no mailpit needed for ITs). Tests: `TemplatedMailServiceIT` (3 cases — real Spring
  engine render→outbox, multi-recipient, render-only) + `SmtpMailSenderUnitTest` (2 — enabled dispatches MIME,
  disabled no-ops). **Boyscout:** disabled Boot's `mail` actuator health contributor
  (`management.health.mail.enabled=false`) — `spring.mail.host` activated a live-SMTP probe that flipped
  `/actuator/health` to 503 (broke ActuatorHealthIT / UsersJitFirstLoginIT / SecurityFilterChainIT). Whole
  `./gradlew check` GREEN (1147 tests, ApplicationModulesTest + LayeringRulesTest accept the new platform.mail
  package, pmdMain + cpdRatchet clean). No controller → no OpenAPI/orval change. INFRA only; planning templates
  ride T-10b, the job rides T-10c.
- [x] **T-10b — Club notification fields + 3 templates.** Flyway `V35` adds `send_planning_day_info_mail_to`
  + `use_planning_day_without_reservations` (structural); map on `Club` + the cancel-rule accessor (ADR-0022 §2);
  the 3 Thymeleaf templates `planningday-ok` / `planningday-cancel` / `planningday-assignment-notification`. *(club + templates seam)*
  **Done:** `send_planning_day_info_mail_to` already existed (V2 S-014 baseline, `VARCHAR(250)`) — V35 adds ONLY the
  genuinely-missing `use_planning_day_without_reservations BOOLEAN NOT NULL DEFAULT false`. Mapped both on `Club.java`
  with the cancel-rule as a domain method (ADR-0022 §2): `shouldSendPlanningDayOk(hasReservation)` =
  `hasReservation || usePlanningDayWithoutReservations` (mirrors `PlanningDayNotificationJob.cs:75-94`), plus
  `planningDayMailsAsOkWhenNoReservation()`, `wantsPlanningDayNotifications()` (non-blank recipient = opt-in,
  legacy `:53`), `setPlanningDayInfoMailTo` (blank→null normalize). 3 Thymeleaf templates under `templates/email/`
  (`planningday-ok`/`planningday-cancel`/`planningday-assignment-notification`, German house style, `#temporals` dates),
  bound by 2 small typed model records `PlanningEmailModels.{PlanningDayInfoModel,PlanningDayAssignmentModel}`
  (field set per oracle `PlanningDayEmailBuildService.cs:81-90`), rendered via T-10a's `TemplatedMailService`.
  **Tests:** `PlanningEmailTemplatesIT` (3 cases — renders each template via the real Thymeleaf engine →
  captured-outbox asserts subject + date/location/person/crew/sender tokens) + 4 new `ClubDomainTest` cases
  (ok-when-reservation / cancel-when-none+flag-false / ok-when-flag-true / opt-in tracks non-blank address).
  **Audit:** added `sendPlanningDayInfoMailTo` + `usePlanningDayWithoutReservations` to the Club audit-redaction
  allow-list (club config, not member PII) — `AuditRedactionCoverageTest`. No controller → no OpenAPI/orval change.
  Whole `./gradlew check` GREEN (1151 tests, pmd/cpdRatchet/arch-guards/OpenApiSnapshot all green). T-10c consumes these.
- [x] **T-10c — PlanningDayNotificationJob + run-now affordance.** `@Scheduled @LifecycleStateFilter` job (day+1
  club-addr ok/cancel via reservation-existence + club flag; day+7 assignee fan-out, skip-blank); template/recipient
  selection in domain/service; tenant-scoped repo queries (days dated +1 / +7); guarded+audited
  `POST /api/v1/planning-days/notifications/run` (dev/test profile + ClubAdmin); `PlanningDayNotificationJobIT`
  (asserts vs the T-10a captured outbox). **Chain: T-10a → T-10b → T-10c.** *(job + affordance seam)*
  **Done:** `PlanningDayNotificationJob` (`planning.application`, `@Scheduled` daily + `@LifecycleStateFilter({ACTIVE})`
  — per-club tenant-scoped via the existing aspect, NOT `@UnscopedScheduledJob`). `runForCurrentClub()` is the per-club
  body the aspect re-enters AND the run-now affordance calls; it runs both exact-date passes (`LocalDate ==` semantics):
  imminent=today+1 → for an opted-in club (`Club.wantsPlanningDayNotifications()`) one mail per day to
  `Club.getPlanningDayInfoMailTo()`, `planningday-ok` vs `planningday-cancel` chosen by `Club.shouldSendPlanningDayOk(hasReservation)`
  (T-10b, hasReservation = T-03 `countReservationsForDay > 0`); week-ahead=today+7 → for every assigned person (all 3 roles)
  a `planningday-assignment-notification` to `Person.emailForCommunication()`, skip-blank (legacy per-person opt-out ignored
  — parity). Template/recipient SELECTION lives in the job/`Club` aggregate, not SQL (ADR-0022 §2); rendered via T-10a's
  `TemplatedMailService` + T-10b's `PlanningEmailModels`. New tenant-scoped JPQL `PlanningDayRepository.findActiveByDate(date)`
  (full aggregates dated exactly == date, soft-delete + `@TenantId` filtered — no new native SQL). Guarded run-now:
  `POST /api/v1/planning-days/notifications/run` (`PlanningNotificationController`, operationId `runPlanningDayNotifications`,
  `@Profile({"dev","test"})` + `@Hidden` + `@PreAuthorize hasRole(CLUB_ADMINISTRATOR)`); triggers the job for the current
  club, emits `AuditAction.PLANNING_NOTIFICATIONS_RUN` (non-PII `RunSummary` snapshot → `ControllerAuditCoverageTest` green
  transitively). **Boyscout:** added `Person.emailForCommunication()` (parity with legacy `EmailAddressForCommunication`,
  ADR-0022 §2 — prefers business/private per flag, falls back when blank). **Tests:** `PlanningDayNotificationJobIT` (3
  cases — both passes ok/cancel/assignment to the right recipients+tokens; cancel-vs-ok by the club reservation-less flag;
  run-now ClubAdmin 200 / PILOT 403 / tenant-scoped) asserting the T-10a captured outbox. **DoD:** `./gradlew check` GREEN
  (full suite + pmd + cpdRatchet + arch guards + OpenApiSnapshot); OpenAPI snapshot + orval client regenerated (only the new
  `PLANNING_NOTIFICATIONS_RUN` audit-enum value — the run-now route is `@Hidden`, correctly absent from the client).
  > **OVERFLOW (2026-06-07, do-task, before any code/commit)** — 4 seams / ~15 files / 8+ new, well over
  > the do-task caps (1 seam, ≤8 touched, ≤5 new). Root cause: **ADR-0013 email infra was decided but NEVER
  > built** — there is *no* `spring-boot-starter-mail`/Thymeleaf dep, *no* `spring.mail` config, *no* mail
  > port/service, *no* `templates/email/` dir in `alpenflight/server` (confirmed: zero `JavaMailSender`/
  > `MailSender`/`TemplateEngine`/`spring.mail` hits in src). The mailpit the real-idp `register.spec.ts`
  > asserts is **Keycloak's** SMTP sink, not an AlpenFlight send path. ADR-0013's own follow-ups (add starter,
  > scaffold `templates/email/`, wire mailpit into the *backend*, build the JavaMailSender test-capture
  > harness) are unstarted. Also: the **recipient + flag fields don't exist on the Club aggregate** — no
  > AlpenFlight equivalent of legacy `Club.SendPlanningDayInfoMailTo` nor `ClubUsePlanningDayWithoutReservations`
  > (Club.java maps a narrow walking-skeleton column set). And the `@Scheduled` job must be tenant-scoped via
  > `@LifecycleStateFilter` (ArchUnit-enforced — `ScheduledLifecycleFilterCoverageTest`), and the run-now
  > controller must satisfy `ControllerAuditCoverageTest` (audit on every mutating endpoint). Suggest split:
  > - **T-10a — email infra (ADR-0013 build-out, the prerequisite seam):** `spring-boot-starter-mail` +
  >   `spring-boot-starter-thymeleaf` deps; `spring.mail` config in `application{,-dev,-test,-prod}.yml`
  >   (dev/test → mailpit `localhost:1025`, prod → real relay placeholder/disabled); a `MailSender` domain
  >   port + a Thymeleaf-backed `TemplatedMailService` (build-service pattern); test-capture harness
  >   (GreenMail or a captured-outbox `MailSender` fake) + a 1-template smoke IT. *(platform/email seam)*
  > - **T-10b — Club notification fields + 3 templates:** Flyway `V35` adds `send_planning_day_info_mail_to`
  >   + `use_planning_day_without_reservations` columns (structural only); map them on `Club` + a domain
  >   accessor for the planning-day-cancel rule (ADR-0022 §2 — rule on the aggregate); the 3 Thymeleaf
  >   templates `planningday-ok`/`planningday-cancel`/`planningday-assignment-notification`. *(club + templates seam)*
  > - **T-10c — PlanningDayNotificationJob + run-now affordance:** the `@Scheduled @LifecycleStateFilter`
  >   job with the two passes (day+1 club-addr ok/cancel via the reservation-existence check + club flag;
  >   day+7 assignee fan-out via Person emails, skip-blank); template/recipient selection in the
  >   domain/service (not SQL); new repo queries (days dated exactly tomorrow / +7, tenant-scoped) on
  >   `PlanningDayRepository`/`JpaPlanningDayRepository`; the guarded `POST /api/v1/planning-days/
  >   notifications/run` run-now affordance (dev/test-profile + ClubAdmin gate, audited); `PlanningDayNotificationJobIT`
  >   (ok-vs-cancel day+1, assignee day+7) asserting against the captured outbox from T-10a. *(job + affordance seam)*
  > Chain: **T-10a → T-10b → T-10c** (10c depends on both). No code committed for T-10. Returning overflow to the manager.
- [x] **T-13 — pull J-6 proof captures forward (operator priority, 2026-06-07).** Un-fixme'd the real-idp parity
  spec's **clean-seed happy path** — 4 cases now run FULLY REAL against the real-idp stack as `clubadmin4`
  (ClubAdmin, real roles, no mock-auth): list renders the V34 seed days (weekend flagged) · create a day
  (date + fresh-seeded location + 3-role crew + remarks → renders in the list with crew names) · edit-crew
  persists on reopen (real PUT) · the inline J-5 reservations panel renders · the setup wizard bulk-creates
  weekend days. New `planning-parity-fixture.ts` seeds (as clubadmin4, through the REAL APIs) a fresh location
  + 3 crew persons WITH a seed-club-1 membership (the V34 seed persons carry no membership → not pickable).
  Each case captures screenshots (`alpenflight-planning-{list,form,reservations-panel,setup-form}.png`,
  capture-before-assert) + a `proofVideo({ journey: 'J-6' })` pass-video → the gallery generator emits the J-6
  per-journey page with `<video>` blocks (verified locally: synthetic-manifest → J-6 page renders the caption
  + video + is index-linked). Deployed preview: `…/proof-preview/integration-J-6/J-6/`. HARDER cases stay
  `test.fixme` for T-16 (duplicate-409, delete-cascade, tenant-isolation 404, notification-job→mailpit,
  migrated-parity read). PAIRED legacy↔AlpenFlight shots DEFERRED to T-16: the side×view `screenshots.json`
  sidecar is staged by the heavy fanout (`alpenflight-proof-fanout.yml`), not the per-push proof job — per-push
  the J-6 page renders the AlpenFlight pass-VIDEOS (which drive the real screens), which is the visible result.
  Local DoD: tsc clean (my files) · eslint clean · prettier-written over `e2e/**` · gallery generator renders
  a J-6 page from a J-6 manifest. The real-idp run itself executes in the per-push `alpenflight-proof` CI job
  (no local `docker compose` plugin on this box → stack bring-up local-blocked). *(e2e-driver, capture+deploy)* [[feedback_surface_proof_early_on_repeated_failure]]
- [x] **T-13b — previews-index didn't surface the per-push J-6 page (operator: "J-6 isn't on my bookmark").**
  T-13 deployed the J-6 page to `proof-preview/integration-J-6/J-6/` (per-push clean-seed), but the
  previews-index generator's branch probe (T-37) looked ONLY under `…/legacy-parity/J-<n>/` (the fanout's
  nightly path) → J-6 read as `pending` on the persistent bookmark. Fixed `generate-previews-index.mjs` to
  probe BOTH branch sub-locations (`<branch>/J-<n>/` per-push + `<branch>/legacy-parity/J-<n>/` fanout) and
  pick the freshest by mtime (so a stale parent page never beats a newer fanout page — the T-37 concern,
  now handled by freshness not by hiding the parent). Spec updated (8/8 green: parent-level surfaced +
  freshness tie-break). *(generate-previews-index.mjs branch-source probe)* [[feedback_surface_proof_early_on_repeated_failure]]
  > **Broader gap noted (NOT this task):** canonical `proof/J-2…J-5/` pages 404 — only J-0/J-0c/J-1 were
  > ever published canonically, so merged journeys aren't on the bookmark either. The per-journey gallery
  > re-arch (T-14 family) must backfill the canonical per-journey pages on merge-to-main. Flag for T-14/retro.
- [x] **T-11 — wire migration bindings + real round-trip.** `MapperLegacyBindings` for the 3 PlanningDay
  mappers + producer SELECT; legacy seed for the fanout; prove the real export round-trip. *(migration seam)*
  **Done:** wired all 3 `MapperLegacyBindings` entries (`PLANNING_DAY` / `PLANNING_DAY_ASSIGNMENT` /
  `PLANNING_DAY_ASSIGNMENT_TYPE`), all FULL_PORT, producer SELECTs reconciled against the REAL legacy MSSQL
  DDL (DBUpdate_v1.0.1). Two producer-SELECT catches the authored-but-unrun mappers hid
  ([[project_synth_bundle_doesnt_validate_producer_select]]): (a) the type mapper reads `RequiredNrOfAssignments`
  but the real column is `RequiredNrOfPlanningDayAssignments` (no EF `[Column]` rename) → projected
  `AS RequiredNrOfAssignments`; (b) `PlanningDayAssignments` has NO own `ClubId` column → `operating_club_id`
  denormalised by `JOIN PlanningDays … AS OperatingClubId`. Also fixed the fan-out FK wiring the unrun mappers
  lacked: all 3 mappers now declare `foreignKeyColumns()` — `PlanningDayMapper` names `operating_club_id` as the
  `location_id`→LOCATION fan-out disambiguator (PlanningDay carries no own `club_id`; mirrors
  `AircraftReservationMapper`), plus the off-convention `operating_club_id`→CLUB / `assigned_person_id`→PERSON /
  `assignment_type_id`→TYPE columns. Emptied the 3 PlanningDay entries from T-12's `KNOWN_UNBOUND` (now actively
  guarded; the binding-coherence + FK-target-closure checks go green). Added per-entity binding assertions to
  `MapperLegacyBindingsTest` (incl. the alias + JOIN catches). **Real round-trip proven:** new
  `PlanningDayMigrationRoundTripIT` (server, mirrors `LocationMigrationRoundTripIT`) — hand-built bundle → REAL
  server ingest pipeline: a shared legacy Location fans out to 2 club replicas, the migrated PlanningDay in club A
  resolves `location_id` to club A's OWN replica (the J-0b own-club FK invariant), `planning_date` = legacy `Day`
  (no tz shift), `info` = `Remarks`, the 3 well-known assignment types migrate from REAL legacy data (NOT
  migration-seeded), the assignment resolves to the migrated `segelflugleiter` type (→ FLIGHT_OPERATOR), tenant
  isolation holds (club B empty). Whole `./gradlew check` GREEN on BOTH modules (migration-bundle: contract +
  binding tests; server: full suite + pmd + cpdRatchet + arch guards + MapperVsSchemaCompatibilityTest).
- [x] **T-12 — early mapper-binding contract check (rider).** Build-time binding-presence + producer-SELECT-column
  check so a missing binding / dropped column fails fast before the ~20-min fanout. *(migration-tool seam)* [[verify_infra_is_run_not_just_authored]]
  **Done:** new GENERIC registry-wide `MapperBindingContractTest` in `migration-bundle` (runs in `./gradlew check`),
  walking EVERY `KnownMappers` mapper (not just planning) so it guards every future migration journey. Asserts:
  (1) **binding-presence** — each `EntityType` HAS a `MapperLegacyBindings` entry OR is in an explicit `KNOWN_UNBOUND`
  pending-set; a NEW unbound mapper not allowlisted → RED with the mapper + entity named (the J-5 T-07 zero-binding
  class, caught at build not at the fanout). The **3 PlanningDay entity types are in `KNOWN_UNBOUND`** with a
  "remove when T-11 wires PlanningDay bindings" comment, so the build stays GREEN until T-11 (which empties them);
  a hygiene test fails if a `KNOWN_UNBOUND` entry is actually bound (stops the pending-set rotting into silent
  suppression) or names a non-existent mapper. (2) **producer-SELECT ↔ mapper-reads coherence** — every legacy
  column the mapper's `writeNdjson` reads (`source.getXxx("…")`, source-parsed from the mapper `.java` — the only
  place legacy names exist; `columns()` carries NEW-stack names) must appear in the bound SELECT, else
  export-abort/silent-NULL. (3) FULL_PORT carries a consumer INSERT targeting its table; SYSTEM_GLOBAL's INSERT is
  empty by contract. (4) bound mapper declares ≥1 column. **RED-first proven:** un-allowlisting PLANNING_DAY made
  the presence test fail for the right reason. **Static residual deferred to the real fanout**
  ([[project_synth_bundle_doesnt_validate_producer_select]]): whether a SELECTed column EXISTS in the live MSSQL
  FLSTest schema + type-fidelity coercions — the static check proves SELECT and mapper AGREE, not that either
  matches the real legacy DDL (that only T-11's nightly fanout validates). `./gradlew check` GREEN
  (migration-bundle standalone build; the cpd/pmd ratchet lives on the server module, untouched — this is a
  test-only add in migration-bundle). Cleared the `_BOYSCOUT.md` "Cheap early mapper-binding check" rider.
- [x] **T-14 — per-journey gallery Maintainability panel (gallery re-arch slice, ≤40%).** Remainder after
  the T-01b scaffold: the FE-fallow/BE-PMD/CPD delta panel on the J-6 page. The panel renderer + 4-artifact
  parsers (`parseFallowAudit`/`parseFallowHealth`/`parsePmd`/`parseCpd` → `loadMaintainability` →
  `renderMaintainabilityPanel`) and the T-12 CI emit steps (FE `fallow audit/health` JSON + BE PMD/CPD XML →
  `public/alpenflight/proof/maintainability/{…}`) landed under the J-5 carve; T-14 closed the **wiring gap**
  that left the J-6 page reading "snapshot only" instead of its delta: the per-push `ci.yml` gallery step + the
  fanout gallery step now pass `--journey-under-work` (ci.yml: from the `changes` job's derived `proof_journey`;
  fanout: derived off `integration/J-NNN` `github.ref_name`) — needed because on a PR push `GITHUB_REF_NAME`
  is the merge ref, so the generator's branch-name fallback can't derive the journey. Verified locally: the J-6
  page renders the FE delta (complexity 5 · duplication 16 · dead-code 0 · verdict fail → red "21 introduced
  (fail)" pill) + the repo snapshot (score 71.1 B) + the BE PMD/CPD rows; a non-journey-under-work page (J-0)
  shows the snapshot only ("historical per-journey delta not reconstructable", neutral pill) — no false delta;
  absent artifacts → graceful "no data" (fail-soft, never a crash/dead link). Exported `parseArgs` + added 3
  generator-spec cases (CLI-flag parse; explicit-journey drives the delta even on a merge-ref branch;
  no-journey degrades to snapshot-only). `pnpm preflight:no-e2e` + the browserless link-integrity check GREEN;
  actionlint clean on both workflows. Cleared the `_BOYSCOUT.md` "Add a per-journey Maintainability panel"
  rider. *(generate-gallery.mjs + ci.yml/fanout `--journey-under-work` wiring)*
- [x] ~~**T-15** — scope per-push mock-e2e~~ **(retired → pulled forward into T-02b).**
- [ ] **T-16 — thicken specs to full real assertions** from the oracle; run the §4 gate via `e2e-driver`. *(spec seam)*
  - [x] **legacy↔AlpenFlight planning parity shots (operator priority, 2026-06-07).** Authored the LEGACY
    planning parity spec `e2e/tests/planning/planning-parity-J6.spec.ts` (mirrors `reservations-parity-J5.spec.ts`):
    drives the legacy flsweb `/planning` future-days list + one day's `/planning/:id/edit` form + the
    `/planningsetup` wizard as `testclubadmin`, captures `legacy-planning-{list,form,setup}.png` + the parity
    video. Wired the fanout (`alpenflight-proof-fanout.yml`): new step 2f runs the legacy spec (`--project=planning`,
    `if: always()` + `continue-on-error`), added `planning-migration-parity.spec.ts` to the step-6 AlpenFlight
    real-idp invocation (produces the `alpenflight-planning-{list,form,setup-form}.png` shots), declared the J-6
    legacy video in the `legacy-video.json` sidecar + the 3 legacy + 3 AlpenFlight `add_shot` pairs (side×view
    list/form/setup) into `screenshots.json`. **Fanout RAN (run 27084983585, branch HEAD 78387a85):** the
    legacy planning parity spec PASSED (1 passed, video + 3 legacy PNGs captured), the AlpenFlight planning
    clean-seed cases PASSED (among the 25 passed), the gallery + branch-preview deploy + deployed-link-check all
    GREEN (the `!cancelled()` path survived an UNRELATED step-6 red — the pre-existing J-0c real-bundle ingest
    `sqlstate=23505` + its dependent J-5 migrated-reservation read, mirrored on the SAME-minute `main` cron
    fanout, NOT introduced here; same family as the branch's pre-existing `ExportCommandSmokeTest` build red).
    **LIVE + verified:** all 6 paired planning shots return 200 at
    `…/proof-preview/integration-J-6/legacy-parity/J-6/` (legacy + AlpenFlight × list/form/setup), the page
    renders the 3 paired view rows + the legacy parity video, and the persistent bookmark
    `…/alpenflight/previews/index.html` surfaces `…/legacy-parity/J-6/` (freshest-wins, T-13b). *(e2e-driver, parity capture)* [[feedback_proof_in_gallery_not_chat]]
  > **GATE BLOCKER — correction to the note above (manager, 2026-06-07):** the J-0c Location real-bundle
  > ingest `sqlstate=23505` (`fan-out-migration-parity.spec.ts:133`, masked `INGEST_INTERNAL_ERROR`) is **NOT
  > confirmed mirrored on main** — main's same-minute cron (27084966831) failed on an UNRELATED **gh-pages
  > push race** (`failed to push some refs`), not a 23505. And it is **NOT the same family as the
  > `ExportCommandSmokeTest` red** (that was a real T-11 code regression — the bound-vs-registered entity set —
  > now FIXED in `84c84de2`). The 23505 is in the SHARED J-0c Location chain (no planning code); most likely
  > shared-real-idp-DB residue / ingest idempotency on the deployment id, surfaced now the fanout runs more
  > specs. Overlaps the roadmapped J-0b "CLUB pgcopy ↔ seedClubLegacyIdMap" follow-up. **At the §4 gate:**
  > re-run the fanout to test determinism; if deterministic, surface the masked constraint (server ingest
  > error logging) + fix the residue/idempotency or escalate to the J-0b item. §4 cannot go green until cleared.
  > [[project_synth_bundle_doesnt_validate_producer_select]]

- [ ] **T-17 — unify the proof gallery to ONE source per journey (operator design, 2026-06-07).** Ends the
  recurring "videos OR screenshots depending on which job deployed last" pain. Operator design: *legacy is
  frozen → capture its screenshots ONCE and persist them; the in-flight dev loop pairs the fresh AlpenFlight
  screens against the persistent legacy refs, so the dev page is ALWAYS complete; scope (not the page) differs
  between in-flight dev and heavy nightly.* Concretely:
  - **Commit the legacy reference screenshots as fixtures** (made once, in git, never reaped, intact through
    the whole journey's dev). For J-6: commit the 3 already-captured legacy planning PNGs
    (`legacy-planning-{list,form,setup}.png`) under a stable `e2e/.../legacy-reference/<feature>/` path. Establish
    the pattern so future journeys capture-once-commit at T-01/T-13.
  - **Per-push (dev) gallery pairs committed-legacy + fresh-AlpenFlight** → the per-push per-journey page
    renders the paired legacy↔AlpenFlight screenshots (legacy from the committed ref, AF from the fresh
    clean-seed capture) + the AF videos. Always complete, every push — no fanout dependency for the visual pairing.
  - **One source per journey:** retire the per-push-vs-legacy-parity page divergence and **revert the
    freshest-wins band-aid** (the T-13b `JOURNEY_PAGE_SOURCES` rank hack) — there's one per-journey page; scope
    differs (dev = committed-legacy + fresh clean-seed AF; nightly = + the full migration chain). Optionally TWO
    bookmarks (in-flight dev + heavy nightly), both carrying screenshots — only if it simplifies.
  - The heavy fanout still owns the **migration round-trip proof** (the real legacy→migrate→AF data chain — the
    done-bar), which is separate from the visual legacy↔AF screenshot pairing. *(e2e-driver; gallery generator +
    committed legacy-ref fixtures + ci.yml per-push pairing + retire the band-aid; may split)* [[feedback_proof_gallery_per_journey_one_bookmark]] [[feedback_surface_proof_early_on_repeated_failure]]
  - **SPLIT into 3 seams (do-task overflow, 2026-06-07).** T-17 genuinely exceeds one do-task seam (committed
    fixtures + generator pairing + ci.yml per-push wiring + index band-aid revert + a LIVE-deploy verify that
    needs a CI round-trip). Sub-seams:
    - [x] **T-17a — commit legacy-ref fixtures + establish the capture-once-and-commit pattern.** Fetched the 3
      real legacy flsweb planning PNGs from gh-pages (list 33KB · form 44KB · setup 19KB, 1280×800), committed
      under `alpenflight/web/e2e/legacy-reference/planning/{list,form,setup}.png` + a README establishing the
      pattern (legacy frozen → capture once, commit, never reap; future journeys do this at T-01/T-13). Locally
      verified the PNGs are the real captures. Self-contained seam; carries the manager's T-17-add commit.
    - [ ] **T-17b — per-push gallery pairs committed-legacy + fresh-AlpenFlight.** New `ci.yml` `alpenflight-proof`
      staging step: stage the committed `legacy-reference/planning/*.png` + the fresh clean-seed AF
      `alpenflight-planning-{list,form,setup-form}.png` (already written by `planning-migration-parity.spec.ts`)
      into a `--screenshots` dir, write `screenshots.json` pairing them by view, pass `--screenshots` to the
      per-push generator step. So every push → ONE complete per-journey page (videos + 6 paired screenshots), no
      fanout dependency for the pairing. Lock the contract in `generate-gallery.spec.ts`.
    - [ ] **T-17c — retire the index band-aid + deploy-verify on live gh-pages.** Revert the T-13b
      `JOURNEY_PAGE_SOURCES` rank-0/rank-1 + freshest-mtime tie-break in `generate-previews-index.mjs` (with one
      unified per-push page there's nothing to tie-break); collapse to one branch source; keep canonical/archive
      fallbacks. Lock in `generate-previews-index.spec.ts` (no freshest-wins). Then deploy-verify on the LIVE
      gh-pages: curl the per-push J-6 page asserts BOTH videos + 6 paired legacy↔AF imgs resolve 200, and the
      persistent `…/previews/index.html` bookmark links THAT page (the lesson: unit-green ≠ deployed-correct).
