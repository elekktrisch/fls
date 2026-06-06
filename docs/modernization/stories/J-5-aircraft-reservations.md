---
id: J-5
title: Aircraft reservations
epic: E-08
status: in_progress
started_at: 2026-06-06
journey0: false
carved: true
depends_on: [J-1]
rolls_up: [S-068, S-069]
acceptance:
  - "[happy] Create a timed reservation (aircraft + pilot + location + start/end) → persists, appears in the list and on the scheduler in the aircraft's lane at the right time."
  - "[happy] All-day reservation (IsAllDayReservation) stores start=end=day, renders as a full-day band."
  - "[key-error] A second reservation overlapping an existing one on the SAME aircraft + time range is rejected 409 Conflict; editing a reservation does NOT conflict with itself."
  - "[happy] Edit moves time / changes type; delete soft-deletes and the slot frees (a new overlapping reservation then succeeds)."
  - "[edge] Cross-tenant aircraft (legacy-open parity, operator 2026-06-06): an operating club MAY reserve an aircraft managed/owned by a different club with NO charter gate — the reservation is stamped with the operating club's tenant and the aircraft FK crosses tenants freely (matches legacy: no tenant gate on the aircraft picker, no charter concept). The spec asserts the positive cross-tenant reservation succeeds."
  - "[happy] Paginated list endpoint `POST /api/v1/aircraftreservations/page/{start}/{size}` returns the SPA-shaped page; reservation-type dropdown loads from `/aircraftreservationtypes/listitems`."
screen: /reservations (+ /reservation-scheduler calendar view of the same data)
headless_pulled_in: none
migration: AircraftReservation + AircraftReservationType (mappers already authored+unit-tested in migration-bundle — needs a real round-trip)
parity_test: alpenflight/web/e2e/tests/reservations/reservations-crud.spec.ts
adr_refs: [0005, 0008, 0022]
---

## Context
Reservation conflict detection is real domain logic — the first journey whose load-bearing
proof is a *rejection* (overlap → 409), not just a round-trip. A club books an aircraft for a
window (timed or all-day); the system must refuse a second booking that overlaps the same
aircraft, while allowing an edit of the existing one and allowing legitimately-adjacent
bookings. The screen replaces legacy `flsweb/src/reservations/` (list + edit) and
`flsweb/src/reservation-scheduler/` (the aircraft×time grid) — one reservation route family.

## Spec must assert
The happy path + the conflict rejection, grounded in legacy behavior:

- **Conflict = overlap on the same aircraft**, soft-deleted rows excluded, **self-excluded on
  update**. Legacy enforces this in the service layer over a GiST range probe — the schema
  deliberately has **no** `EXCLUDE` constraint (`V4__…sql:128-130`: legitimate-overlap rules
  exist, so the rule lives on the aggregate per ADR 0022 directive 2). Spec proves: overlap →
  409; edit-in-place of the overlapping row → OK; adjacent `[)` ranges (end==next start) → OK.
- **All-day vs timed.** `IsAllDayReservation` ⇒ start=end=day (legacy `ReservationEditController.save`
  collapses to `YYYY-MM-DD`); timed ⇒ explicit start/end on the day. `reservation_range` is the
  generated `tstzrange(start,end,'[)')` (`V4__…sql:115-118`).
- **Cross-tenant aircraft rule — legacy-open (operator 2026-06-06).** Aircraft is a cross-tenant
  aggregate (J-1, `managing_club_id` + metadata `owner_club_id`). Legacy has **no charter model and no
  tenant gate** on the aircraft picker (oracle: `AircraftService.GetAircrafts()` returns all clubs'
  aircraft; `InsertAircraftReservationDetails` never validates ownership) — so J-5 ships **parity**: any
  operating club may reserve any aircraft; the reservation is tenant-stamped with the operating club. No
  charter-agreement model is invented (it doesn't exist in the schema — only `managing_club_id` +
  unused-metadata `owner_club_id`). The spec asserts a cross-tenant reservation **succeeds** (200). A
  structural charter/tenant gate is deferred to a future charter journey when a real charter model exists.
- **SPA-compat endpoints** (preserve wire shape for the ported screen):
  `POST /api/v1/aircraftreservations/page/{start}/{size}` (paged list, `{Sorting,SearchFilter}` body),
  `GET /aircraftreservations/future`, `GET /aircraftreservations/{id}`,
  `POST /aircraftreservations` (create), `POST /aircraftreservations/{id}` + `X-HTTP-Method-Override: PUT`
  (update — map to a real `PUT`/`PATCH` on the new API), delete, `GET /aircraftreservationtypes/listitems`.
- **Scheduler render.** A created reservation appears in the correct aircraft lane at the correct
  time offset on the calendar view. Legacy is a hand-built grid (`rowHeight 30`, `cellWidth 8px/hr`,
  `hoursPerDay 24`, persisted `AircraftIdsToDisplayInScheduler`) — the new view need not copy the
  pixel grid; assert lane×time placement of the row.

## Notes

**60/40 framing.** ≥60% is the new reservations vertical (the `AircraftReservation` aggregate +
conflict domain rule + CRUD/paged API + the `/reservations` screen + the calendar view). The
≤40% tech-debt budget is filled by riders below that touch this journey's surface — they ride the
gate, not their own journey ([[feedback_journey_is_a_60_40_sprint]]).

**Seam hints (non-binding, for `/do-ship` sizing):**
- `AircraftReservation` aggregate (+ `AircraftReservationType` value/lookup) — owns
  `validateDuration()` (end>start, no empty range) + `conflictsWith()` overlap on the aggregate,
  NOT the schema. One aggregate = one task.
- Reservations resource — the endpoint cluster above (list/page/get/create/update/delete/types). One
  resource = one task (may split paged-list from CRUD if it overflows the sizing gate).
- Conflict-detection query — the GiST range probe (`btree_gist` on `(aircraft_id, reservation_range)
  WHERE deleted_on IS NULL`, `V4__…sql:128`); a native-SQL register entry is needed (tenant-scoped
  table → `NativeSqlRegisterTest`). Every mutating controller method needs its own audit event
  (`ControllerAuditCoverageTest`) — create/update/delete each emit one.
- `/reservations` list+edit component, and the `/reservation-scheduler` calendar view — **the
  drag-move/resize interaction is the heaviest seam**; size it as its own task(s). If the calendar
  interaction overflows a clean worker, `/do-ship` re-plans it into sub-tasks (it does not become a
  follow-up — drive to goal with tasks).
- Migration: the mappers (`AircraftReservationMapper` + `AircraftReservationTypeMapper`) already
  exist + unit-pass in `migration-bundle/…/accounting/`; authored ≠ proven
  ([[verify_infra_is_run_not_just_authored]]). The done-bar needs a **real legacy→migrate→AlpenFlight
  round-trip** (fanout), not just synth — reservations reference Aircraft/Person/Location, so seed
  those legacy rows + verify fan-out keying ([[project_synth_bundle_doesnt_validate_producer_select]]).

**Candidate riders to fold into the ≤40% budget** (`/do-ship` confirms which fit this gate):
- **Docker disk leak / local self-verify** (`PostgresTestContainerLifecycle` pre-start sweep + raise
  the 60s readiness cap + a Stop hook) — the backend slice's ITs hit exactly this harness; operator-
  approved at J-4 retro. [[project_docker_disk_leak_orphaned_testcontainers]]
- **J-1 aircraft real-idp spec flake** (`aircraft-migration-parity.spec.ts` retry-isolation + S-163
  45s timeout) — J-5 builds on aircraft and its proof shares the clean-seed job; a stray aircraft
  flake would red J-5's gate. Fix it here (retry-idempotent create + diagnose the slow edit path).
- **Proof-gallery per-journey re-arch — FIRST slice only** (substantial pure tech-debt, split across
  2-3 journeys' budgets): e.g. make the gallery generator **key by `journey`** (the sidecars already
  carry the field) without yet retiring the per-proof-type sub-paths. [[feedback_proof_gallery_per_journey_one_bookmark]]
- **Maintainability pass** (filed by this carve; maintainability = complexity + duplication + dead
  code per operator): commit `alpenflight/web/.fallowrc.json` (honest score 52 D→70 B); build the new
  reservation `*-edit.page.ts` WITHOUT replicating the flagged `formToUpdateRequest`/`finalSubmit`/
  `errorPatch` complexity (extract the shared helper, land low-CRAP); add **PMD+CPD** to
  `alpenflight/server` (the backend's missing complexity/dup/dead-code axis — reservation aggregate is
  the first target); and render a **per-journey Maintainability panel** on the J-5 gallery page (fallow
  `ci` changed-files envelope + the pmd/cpd report). Non-reservation hotspots ride their own next-touch
  journey. [[reference_fallow_maintainability_analyzer]] [[feedback_maintainability_includes_dupes_and_deadcode]]
- Minor: e2e prettier/tsc-strict normalization on touched specs only.

**Assumptions made:**
1. **S-068 + S-069 stay one journey** (roadmap assumption #5: list + calendar are two views of one
   reservation route family). The spec's load-bearing proof is the conflict rejection + CRUD; the
   calendar asserts lane×time placement. The drag-drop scheduler is the heaviest seam — if it can't
   fit J-5's gate cleanly, `/do-ship` re-plans its tasks rather than spinning a J-5b (a re-carve is
   only warranted if the *whole* journey proves oversized at ship time).
2. `X-HTTP-Method-Override: PUT` is a legacy transport quirk; the new API exposes a real `PUT`/`PATCH`
   and the spec drives that — wire-shape parity is the request/response body, not the verb tunneling.

## Assumptions made (do-ship, 2026-06-06 — grounded in the legacy-oracle read)

The oracle found legacy has **no overlap check, no `End>Start` check, no server-side authz, and no
charter/tenant gate** on reservations — several load-bearing J-5 behaviors are therefore **net-new
corrected behavior**, not parity. Recorded decisions (the journey was carved knowing this — "the first
journey whose proof is a *rejection*"):

1. **Overlap→409 is net-new.** Legacy double-books freely (zero conflict logic anywhere). J-5 ADDS the
   guard on the `AircraftReservation` aggregate (`conflictsWith()`), grounded in the V4 schema design
   (`reservation_range` `tstzrange(...,'[)')`, GiST probe, deliberately NO `EXCLUDE` constraint). Rule:
   same aircraft, **half-open** overlap (`existing.start < new.end && new.start < existing.end` → adjacent
   `end==next.start` OK), soft-deleted excluded, **self-excluded on update** → `409` key `aircraft.reservation.overlap`.
2. **`End>Start` enforced** on the aggregate (`validateDuration()`, timed reservations) → `422`; the V4
   header documents this intent. All-day stored as `isAllDay=true` + the date, normalized to the full-day
   span `[date 00:00, date+1 00:00)` for the overlap test (NOT the legacy zero-length `start==end` artifact).
3. **Cross-tenant = legacy-open** (operator 2026-06-06): no charter gate; any club reserves any aircraft.
4. **Deferred refinements (noted, NOT J-5 ACs — ride a future reservations-touch):** server-side
   owner-or-admin edit/delete authz (legacy is advisory/UI-only); server-enforced required-second-crew
   (legacy is client-only); the "maintenance-vs-flight legitimate overlap" type-exception (J-5's rule is
   the simple any-overlap-on-same-aircraft → 409). These don't block the conflict-409 / CRUD / scheduler proof.

## Tasks

Feature ≥60% (T-01, T-03–T-10, T-16) + tech-debt ≤40% riders folded from `_BOYSCOUT.md` (T-02, T-11–T-15).
One seam each; commit directly to `integration/J-5`.

- [x] **T-01 — Spec stub.** Author `alpenflight/web/e2e/tests/reservations/reservations-crud.spec.ts`:
  structure + selectors + flow steps (list, create, edit, delete, conflict-409, all-day, cross-tenant,
  scheduler lane×time) with thin assertions. Commits the screen shape. *(seam: e2e spec)*
- [x] **T-02 — Testcontainers harness hardening (rider).** `PostgresTestContainerLifecycle` pre-start
  sweep of stale `alpenflight-pg-test-*` + raise the 60s readiness cap + a settings.json Stop hook —
  so backend workers self-verify ITs locally. *(seam: PostgresTestContainerLifecycle + settings.json hook)*
- [x] **T-03 — `AircraftReservation` aggregate + `AircraftReservationType`.** Domain entity + factory +
  `validateDuration()` (end>start; all-day full-day span) + `conflictsWith()` (half-open overlap, self-
  exclude). Domain test. *(seam: `aircraft-reservations/domain/`)*
- [x] **T-04 — `JpaAircraftReservationRepository` + conflict GiST probe.** Projection ListRow queries,
  the tenant-scoped GiST range-overlap conflict query (→ `NativeSqlRegisterTest` entry), soft-delete
  filter. *(seam: `aircraft-reservations/infra/`)*
- [x] **T-05 — Reservations CRUD resource.** Service + DTOs + mapper + controller create/get/update/delete
  + `aircraftreservationtypes/listitems`; conflict→409, end≤start→422; explicit `operationId`s (orval
  rider); each mutating method → `ControllerAuditCoverageTest`; ControllerIT. *(seam: reservations resource — CRUD)*
- [x] **T-06 — Paged-list + future/day overview endpoints.** `POST .../page/{start}/{size}` SPA envelope
  `{sorting,searchFilter}`→`{items,pageStart,pageSize,totalRows}` (camelCase house style), `/future`, `/day/{date}`;
  ControllerIT for the page shape + future-excludes-past. Read-shaped POST exempted from the audit guard via
  a new `@ReadOnlyQuery` marker. *(seam: reservations resource — paged-list)*
- [x] **T-07 — Reservation legacy seed + mapper fanout round-trip.** Legacy seed (club, PILOT+person,
  same-club + other-club aircraft, location, type, an existing reservation, `AircraftIdsToDisplayInScheduler`
  setting) + prove `AircraftReservationMapper` + `AircraftReservationTypeMapper` round-trip via fanout
  (real export, not synth). *(seam: legacy seed + migration verify)*
- [x] **T-08 — `/reservations` list page + store + route + api client.** Paged table, store (`withEntities`,
  `saveError`), route, orval client (named methods from the operationIds). *(seam: reservations/list component+store)*
- [x] **T-09 — Reservation edit page (low-CRAP, rider).** Create/edit form (aircraft/pilot/location/start/
  end/type/all-day/second-crew/remarks), conflict-409 inline error, end>start. Build via an **extracted**
  shared form↔request + error-patch helper — do NOT replicate `formToUpdateRequest`/`finalSubmit`/
  `errorPatch` complexity. *(seam: reservations/edit component + shared helper extraction)*
- [x] **T-10 — `/reservation-scheduler` calendar view.** Aircraft×time grid; assert lane×time placement of
  a reservation row. Read-only placement (drag-create/drag-move is the legacy heaviest seam and NOT a J-5
  AC — deliberately not built; `AircraftIdsToDisplayInScheduler` per-user setting deferred). Time→offset
  math in the pure `reservation-scheduler.placement.ts` helper (unit-tested). *(seam: reservation-scheduler component+route)*
- [x] **T-11 — PMD + CPD on `alpenflight/server` (rider).** Gradle `pmd` (built-in, PMD 7.25.0) + CPD
  (`de.aaschmid.cpd` 3.5, `cpdCheck`) wired into `check` with a ratcheting baseline (no hard-fail on
  existing debt). Curated ruleset `config/pmd/ruleset.xml` = complexity (cyclomatic/cognitive/NPath/
  NcssCount/params/methods/fields) + dead/unused code only (no style/naming noise); `pmdMain.ignoreFailures
  = true` (report-only). CPD ratchet `config/pmd/cpd-baseline.txt` (5300 tokens) via the `cpdRatchet` task —
  fails only on duplication GROWTH. Measured on server-main: PMD **65 violations** (34 cyclomatic, 15
  excessive-params, 5 cognitive, 4 NPath, 4 too-many-fields, 3 too-many-methods; **0 dead-code**);
  CPD **2.46%** dup (5300 tokens / 858 lines / 65 blocks over 34,769 LOC). Reservation aggregate clean: only
  2 benign PMD hits (class-sum cyc 62 but max method cyc 9 < 10; 11-param factory) + 9 small DTO/exception
  boilerplate clones, no logic dup. Reports → `build/reports/pmd/main.{xml,html}`,
  `build/reports/cpd/cpdCheck.xml` (T-12 panel feed). *(seam: `alpenflight/server/build.gradle.kts`)*
- [x] **T-12 — `.fallowrc.json` + maintainability report-emit in CI (rider).** Committed
  `alpenflight/web/.fallowrc.json` (honest config — score now **B (71.1)** vs the misleading 52 D; fallow
  confirms it loads the config + excludes `node_modules.windows` + the orval-generated client) + added
  fail-soft (`continue-on-error`) emit steps to **both** proof workflows (`ci.yml` + `alpenflight-proof-fanout.yml`)
  before the gallery-generate step. FE delta = `fallow audit --base origin/main --format json` (changed-files
  envelope w/ per-finding `introduced: true/false` = the journey delta), FE snapshot = `fallow health --format json`,
  BE = `:pmdMain :cpdCheck`. Stable T-13-consumable paths under the gallery `--out` root:
  `public/alpenflight/proof/maintainability/{fallow-audit.json,fallow-health.json,pmd-main.xml,cpd-check.xml}`.
  *(seam: `.fallowrc.json` + ci/fanout emit steps)*
- [ ] **T-13 — Per-journey gallery re-arch (operator ask 2026-06-06) + Maintainability panel (rider).**
  EXPANDED from the planned "first slice" to the full operator-visible result (the operator observed the
  index lists only the active branch + links the all-in-one per-proof-type galleries). Deliver:
  (a) **per-journey pages** — `generate-gallery.mjs` emits ONE page per `journey` (the sidecars already
  carry `journey`; the gallery already groups by it internally) instead of per-proof-type;
  (b) **persistent J-0…J-5 index** — `generate-previews-index.mjs` lists JOURNEYS (not active branches),
  each → its per-journey page, surviving PR close (source J-0…J-4 retroactively from the persistent
  `alpenflight/proof/legacy-parity/` all-journeys archive — screenshots+videos survive on gh-pages);
  the all-in-one paths become SOURCES the per-journey pages read, not destinations;
  (c) **Maintainability panel** on each per-journey page (green/amber/red on that journey's fallow +
  pmd/cpd delta from T-12, link to full report). May split into T-13a (per-journey pages + index) /
  T-13b (maintainability panel) at dispatch if it overflows one clean worker.
  *(seam: generate-gallery.mjs + generate-previews-index.mjs + the gallery-deploy/reap steps)*
- [ ] **T-14 — Scope `alpenflight-proof` to the journey-under-work spec (rider).** Parameterize the
  per-push proof to run only J-5's spec(s) off the integration branch; move full cross-journey regression
  to nightly. *(seam: ci.yml proof spec selection + nightly trigger)*
- [ ] **T-15 — Fix J-1 aircraft real-idp flake (rider).** `aircraft-migration-parity.spec.ts` retry-
  isolation (idempotent create / delta assert) + diagnose the S-163 45s timeout — J-5 shares the clean-seed
  job, a stray aircraft flake would red its gate. *(seam: aircraft-migration-parity.spec.ts)*
- [ ] **T-16 — Thicken spec to full real assertions.** From the oracle: conflict-409 + self-exclude, all-day
  band, cross-tenant-open success, paged envelope shape, scheduler lane×time placement. Final pre-gate. *(seam: e2e spec)*
