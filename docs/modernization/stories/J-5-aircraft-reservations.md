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
parity_test: alpenflight/web/e2e/tests/real-idp/reservations-migration-parity.spec.ts
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
  pmd/cpd delta from T-12, link to full report). **Pre-split per the sizing gate into T-13a/T-13b:**
- [x] **T-13a — per-journey gallery pages + Maintainability panel.** `generate-gallery.mjs` emits ONE
  page per `journey` (keyed by the existing `journey` sidecar field) to a stable per-journey path +
  renders the Maintainability panel reading T-12's 4 artifacts from `public/alpenflight/proof/maintainability/`
  (tolerate any absent — fail-soft). *(seam: generate-gallery.mjs)*
  OUTPUT PATH SCHEME (for T-13b): per-journey pages at `<out-root>/J-<n>/index.html` (e.g.
  `public/alpenflight/proof/J-5/index.html`), emitted only for journeys-WITH-content (video or
  screenshot); pending journeys get no page. Shared media stays at the out-root (`<out>/videos/`,
  `<out>/screenshots/`) and pages reference it via `../`. The all-journeys `<out>/index.html` is still
  written (additive). PANEL: green/amber/red roll-up driven by the FE fallow-audit DELTA on the
  journey-under-work page (green = nothing introduced, amber = something introduced, red = audit
  verdict `fail`); other pages show `neutral`/"snapshot only" (historical per-journey delta isn't
  reconstructable). Snapshot rows (FE health score/grade/MI/dup%, BE PMD violation+complexity+dead-code
  counts, BE CPD dup%) render on every page; any absent artifact → "—"/"no data", never throws. New
  CLI flags: `--no-per-journey`, `--journey-under-work J-N`.
- [x] **T-13b — persistent J-0…J-5 index.** `generate-previews-index.mjs` lists JOURNEYS (not active
  branches), each → its per-journey page, surviving PR close — source J-0…J-4 retroactively from the
  persistent `alpenflight/proof/legacy-parity/` all-journeys archive. The all-in-one per-proof-type paths
  become SOURCES, not destinations. *(seam: generate-previews-index.mjs + gallery-deploy/reap steps)*
- [x] **T-14 — Scope `alpenflight-proof` to the journey-under-work spec (rider).** Parameterized the
  per-push proof: a new `changes`-job step derives the journey-under-work's real-idp spec off the
  integration branch (`integration/J-NNN` → the journey file's `parity_test:` frontmatter first token,
  normalized relative to `alpenflight/web/`) and emits `proof_spec`/`proof_journey`/`proof_is_baseline`.
  The clean-seed `alpenflight-proof` job runs ONLY that single derived spec (`--project=real-idp`).
  FAIL-SAFE: a mock-auth parity spec (J-5's `tests/reservations/…` runs in `alpenflight-mock-e2e`
  instead), a showcase-seeded `tests/profile/` spec (J-4 own gating job), a non-integration branch, or
  any underivable case → the journey-agnostic J-0 Locations baseline (never a no-spec / run-everything
  run). The J-0-caption live-link-check is gated on `proof_is_baseline == 'true'` so a journey-specific
  run can't false-red on absent J-0 captions. Full cross-journey regression stays NIGHTLY
  (`alpenflight-e2e-real-idp.yml`, full `--project=real-idp`) + once at the §4 do-ship gate — never
  per-push. `required` aggregator unchanged (`alpenflight-proof` reports status via the existing chain; a
  scoped green run keeps it green). YAML validated via js-yaml; derivation traced across J-0c/J-1/J-2
  (own spec) and J-3/J-4/J-5/main (baseline). *(seam: ci.yml proof spec selection + nightly trigger)*
- [x] **T-15 — Fix J-1 aircraft real-idp flake (rider).** `aircraft-migration-parity.spec.ts` retry-
  isolation (idempotent create / delta assert) + diagnose the S-163 45s timeout — J-5 shares the clean-seed
  job, a stray aircraft flake would red its gate. *(seam: aircraft-migration-parity.spec.ts)* Done: root cause
  is club A = the shared, never-truncated Flyway `seed-club-1` (two-club-fixture.ts:46), so a failed attempt's
  3 created rows linger and the next attempt's absolute `toHaveCount(3)` saw 6. Fix BOTH: (a) `afterAll`
  DELETEs every created aircraft as the managing-club admin (clean tenant for the next retry) + a `beforeAll`
  list-reset, AND (b) the absolute count → a DELTA (`baseline + 3` + each created row visible by id), the
  residue-proof fallback if a delete is missed. S-163 timeout root cause: the test does in-body fixture-STATE
  setup — `seedAircraftOwnerLink` shells out to Gradle (aircraft-parity-fixture.ts:412) BEFORE any assertion,
  which costs 15-35s on a cold CI daemon and consumes the 45s per-test budget → vague timeout → retry → the
  count residue. Bumped THIS test only to `test.setTimeout(90_000)` with the measured rationale (global 45s
  stays right for the other tests; per-assertion expect timeout unchanged at 5s). CI MUST CONFIRM the S-163
  case no longer times out under load; if it still does, attack the Gradle seeder cost (warm daemon /
  pre-seed in beforeAll), not a further bump. Local Playwright unrunnable here (chrome musl + needs the
  real-idp stack); reasoned from the code + Playwright serial-retry semantics (beforeAll re-runs).
- [x] **T-16 — Thicken spec to full real assertions + author the real-idp gate spec + legacy parity capture.**
  (a) Thickened the inner-loop `tests/reservations/reservations-crud.spec.ts` to FULL assertions
  (dropped `.skip`): conflict-409 + self-exclude, duration-422, all-day full-day band, cross-tenant-open
  success, paged envelope shape, scheduler lane×time placement (10:00 ≈ 41.6% offset). Reconciled the
  T-01 stub against the SHIPPED screens: `res-`→plain-UUID fixtures, `/persons`+`/locations` picker
  routes (not the non-existent `/picker` variants), `isAllDay` field, `selectAfOption` by value, the
  `af-input` date/time markup. (b) Authored the real-idp gate spec
  `tests/real-idp/reservations-migration-parity.spec.ts` (the §4 gate + the scoped per-push
  `alpenflight-proof` run) — clean-seed real chain (real KC clubadmin4 → real backend → real Postgres):
  create→list→scheduler, overlap→409 (+ adjacent OK + self-exclude), duration→422, all-day full-band,
  cross-tenant-open 201 stamped with the operating club, edit/delete-frees; retry-isolation (afterAll
  cleanup + delta asserts + read id from 201 Location). A migrated-data block rides the REAL legacy
  export (T-07 bindings) gated on `J5_BUNDLE_SOURCE=real` (fanout only; `test.skip` otherwise). (c) Wired
  the J-5 legacy parity capture `e2e/tests/reservations/reservations-parity-J5.spec.ts` (legacy flsweb
  reservation list+form+scheduler video + paired legacy↔AlpenFlight list/form/scheduler screenshots) +
  the fanout staging steps (legacy spec run, AlpenFlight spec in the parity invocation, legacy-video +
  screenshots sidecar declarations under journey J-5). RESERVATION-TYPE GAP surfaced (no type-create
  API → clean-seed UI form's required type dropdown is empty; mutations drive the real REST API, list +
  scheduler drive the UI) — reported, not papered over. *(seam: e2e specs + fanout wiring)*
- [x] **T-17 — Clean-seed default reservation-type (done-bar: full UI create on clean seed).** Surfaced by
  T-16: `t_aircraft_reservation_type` is tenant-scoped + migration-populated only (no create API), so a
  clean realm club has zero types → the edit form's required type dropdown is empty → the full UI
  create→type-picker flow can't run on clean seed (only on migrated data). Add a **dev/test-seed default
  `AircraftReservationType`** for the clean-seed test club(s) (mirror how the J-0/J-1 dev seeds populate
  tenant reference rows — a Flyway dev-seed or the two-club fixture seeder), then switch the real-idp gate
  spec's create to drive the UI type-picker (proving the full clean-seed UI create end-to-end). Keep the
  type-create-API itself out of scope (deferred — a clubadmin masterdata screen is its own future journey).
  *(seam: clean-seed dev seed / two-club fixture + the real-idp spec's create flow)*

### §4 gate — gap-hunter findings (2/3 voted not-yet-vertical; domain confirmed honest)
- [x] **T-18 — Fix the migrated-data round-trip to read the migrated tenant (gap-hunter blocker B).** The
  migrated-data block in `reservations-migration-parity.spec.ts` reads as `clubadmin4`/seed-club-1, but the
  migrated legacy reservation is tenant-stamped on the **legacy TestClub** (CLUB is FULL_PORT non-fanout,
  keeps its legacy UUID `0FA7B76F-…`) → invisible to the seed-club-1 reader, so `totalRows >= 1` passes on
  clean-seed residue, NOT the migrated row (the T-07 round-trip isn't proven). Fix: read via the migrated
  tenant (the J-0c `loginAsMigratedAdmin(fixture.clubA)` pattern) + assert the migrated reservation's
  IDENTIFYING field (the unique remark `'Cross-tenant timed reservation (fixture)'` / immat HB-3999 / the
  migrated type), not just a count. Also correct the V31 dev-seed comment (its "seed-club-1 never exists in
  prod" rationale is factually wrong — V5 inserts seed-club-1 unconditionally; it's the same accepted
  dev-seed-in-prod debt as V8/V26/V29/V30, so fix the *justification*, keep the row). *(seam: real-idp spec migrated block + V31 comment)*
- [x] **T-19 — Re-add `alpenflight-proof` to the `required` merge gate (operator decision 2026-06-06; gap-hunter blocker A).**
  The real-idp proof was dropped from `required.needs` on 2026-06-05 because the J-1 aircraft flake red-ed
  unrelated journeys. T-14 (scoped the proof to the journey's OWN spec) + T-15 (fixed that flake) removed
  the reason — operator chose to re-enforce. Add `alpenflight-proof` to the `required` aggregator's `needs`
  + its result-check loop (`ci.yml`), keeping the skipped-to-success path correct (docs-only → skipped →
  green) and confirming the job genuinely reports red on a test failure (not always-green). Net: a red
  real-idp reservations run now blocks merge. Operator accepted the tradeoff (a real-idp infra hiccup can
  block merges — mitigated by the per-journey scoping). Update the stale ci.yml derivation comments that
  still say "J-5 is a mock-auth journey → J-0 baseline" (gap-hunter nit). *(seam: ci.yml `required` needs + result loop)*

### §4 gate — first fanout run reds (genuine regressions caught by the real chain)
- [x] **T-20 — Fix the `_test-fixture.sql` §10 reservation seed column name (fanout seed red).** The fanout
  seed failed `Msg 207, Line 702: Invalid column name 'PilotPerson_PersonId'`. T-07's §10 AircraftReservations
  INSERT used the **v1.0.1 EF6 shadow column** `PilotPerson_PersonId`, but v1.1 superseded it with
  `[PilotPersonId]` (the final FLSTest schema — see the canonical insert `flsserver/database/FLSTest/3 insert/
  4 or 5 Insert Test Data.sql:1129` + `DBUpdate_v1.1.sql:19,75`). Fix §10's column to `[PilotPersonId]`
  (mirror the canonical insert's exact column list); audit §10 for any other stale column vs the final
  schema; check the earlier `Msg 547` FK violations aren't a §10 dangling FK. (`_test-fixture.sql` is OUR
  migration test fixture — editable; the upstream `4 or 5 Insert…` is read-only reference to mirror.)
  *(seam: `flsserver/database/FLSTest/3 insert/_test-fixture.sql` §10)*
- [x] **T-21 — Fix the fanout gallery build step's Node (ESM `.mjs` red).** The fanout's "Build + link-check
  proof gallery" step runs `node e2e/proof-gallery/generate-gallery.mjs` on **Node ~8** (stack trace
  `vm.js`/`bootstrap_node.js`) → `SyntaxError: Unexpected token import`. The SAME generator runs fine in
  `ci.yml` (modern node). Align the fanout step's node env to the ci.yml gallery step (add
  `actions/setup-node@v4` node 20 before it, or invoke via the project toolchain). Investigate whether
  T-12/T-13 regressed it or it's pre-existing-but-only-now-exercised (the per-journey emission may be the
  first ESM-heavy use). *(seam: alpenflight-proof-fanout.yml gallery build step node setup)* **Done:**
  ROOT CAUSE (pre-existing since J-0c #200, NOT a T-12/T-13 regression — the gallery step and the Node-8/20/22
  setups were all born together in `ffa0a5f8`; only now exercised on a real fanout that red-ed upstream). The
  `fanout-proof` job sets up Node 8 (line ~228, legacy flsweb webpack) BEFORE its later Node 20 (~285) / Node
  22 (~504) setups. Those later setups are plain steps (no `if:`), so any upstream failure in the long
  legacy→migrate→real chain SKIPS them — yet the gallery + maintainability steps are `if: always()`, so they
  ran on the last node that actually got set up: **Node 8**, which can't parse `.mjs` ESM. ci.yml's
  `alpenflight-proof` job has NO Node-8 setup, so its byte-identical `always()` gallery step always lands on
  Node 22 — that's the asymmetry. FIX: a single `if: always()` `actions/setup-node@v4`
  (`node-version-file: alpenflight/web/.nvmrc`, identical to ci.yml — = 22.13) re-pinned right before the
  maintainability emit + gallery build + (later) the `rebuild-previews-index` composite, so a failed upstream
  step can never leave Node 8 on PATH for ANY of the three `.mjs`/npx generators
  (`generate-gallery.mjs`, `npx fallow`, `generate-previews-index.mjs`). Legacy-build jobs untouched
  (`legacy-web-build` keeps its own Node 8 for the webpack build). YAML validated with js-yaml + step order
  asserted (re-pin before maintainability/gallery/rebuild). Re-dispatched fanout must confirm the gallery step
  no longer throws `Unexpected token import`.
