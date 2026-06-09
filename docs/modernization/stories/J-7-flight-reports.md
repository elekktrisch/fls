---
id: J-7
title: Flight reports (/flightreports — canned + custom builder + Excel export)
epic: E-07/E-11
status: in_progress
started_at: 2026-06-09
journey0: false
carved: true
depends_on: [J-2]            # reuses J-2's migrated Flight/FlightCrew read-side
rolls_up: [S-065, S-093, S-094, S-095, S-096]
acceptance:
  - "[happy] /flightreports picker renders the canned-report tiles for both categories (person + location); each tile links to /flightreports/:category/:type."
  - "[happy] Pick a canned person report (my-flights-last-30-days): the filter-criteria panel shows the derived date range (today−30 → today) + flight-type toggles (Glider+Motor on, Tow off); summary table has ≥1 grouped row; flights table has ≥1 data row."
  - "[happy] Pick a canned location report (location-flights-this-year): summary groups by FlightTypeName (location reports), flights table populated, scoped to the user's homebase/club."
  - "[happy] Custom builder (/flightreports/custom/:category/:filter/edit): set FlightDate range + flight-type toggles + person/location selector → Apply → results render the filtered set; filter round-trips through the route param."
  - "[happy] Excel export button on a results screen → returns a streamed .xlsx attachment (Content-Type spreadsheet, Content-Disposition attachment)."
  - "[happy] Excel parity: the exported FlightReports .xlsx is cell-for-cell equal to the parity-contract golden fixture (S-093 inventory + oracle-derived, NOT a live legacy export — that byte-match rides the fanout; parity harness green; cosmetic font/width diffs tolerated, values/types not)."
  - "[key-error] Tenant isolation: club-admin from club A filtering by a club-B location sees no club-B flights (empty/scoped, not a leak)."
  - "[edge] A filter matching no flights renders the empty-state copy (no crash, summary + table both empty)."
  - "[edge] Person-report summary groups by crew function (Pilot / Copilot / Instructor / InstructorSoloFlights + Total); tow-flight columns nest under each glider row where a tow exists."
screen: /flightreports   # replacing legacy flsweb/src/reporting/ (FlightReportsModule.js)
headless_pulled_in: "Excel synchronous export infra (S-093/094/095/096) → homed here as the first sync-export consumer: POI ExcelExportSupport helper + the cell-diff parity harness. Scope at J-7 = build helper + harness, cover FlightReports export only. DeliveryMailExport + AircraftStatisticReport parity coverage RIDE J-10 (harness reused, not rebuilt)."
migration: "N/A — read-side. Reuses J-2's migrated Flight + FlightCrew data; no new mapper."
parity_test: alpenflight/web/e2e/tests/real-idp/flight-reports-parity.spec.ts   # FIRST token = ci.yml's "Derive journey proof spec" input (real-idp clean-seed); the mock specs below run via mock_test:, not alpenflight-proof — also: alpenflight/web/e2e/tests/reporting/{flight-reports,custom-builder}.spec.ts
mock_test: alpenflight/web/e2e/tests/reporting/   # journey-under-work's own mock-auth specs (T-02: per-push mock-e2e runs ONLY these; prior journeys' mock specs run at the §4 gate + nightly)
adr_refs: [0005, 0008, 0012, 0024]
---

## Context
The flight-reports screen is the read-side payoff of the J-2 flight data: a pilot
or club admin picks a canned report (their own flights over a window, or their
home-location's flights for a period) or builds a custom one (date range + flight
types + a person/location filter), sees a paginated flights table with a grouped
summary, and exports the result to Excel. This is also the **first synchronous
Excel export** in AlpenFlight, so it drags the POI export infrastructure +
cell-parity harness into existence — the same harness J-10's deliveries/statistics
exports will reuse. ≥60% of the journey is new AlpenFlight feature (the reporting
screen, end to end); the ≤40% tech-debt slot is the export infra + boyscout riders.

## Spec must assert
The contract the green Playwright run proves, grounded in legacy behavior:

**Picker** (`flsweb/src/reporting/flightreports.html`, `FlightReportsModule.js:25-66`):
two categories — `person` and `location` — each a tile grid linking to
`/flightreports/:category/:type`. Canned types to honor (`FlightReportsController.js:120-358`):
- person: `my-flights-today`, `-yesterday`, `-last-7-days`, `-last-30-days`,
  `-last-12-months`, `-last-24-months`, `-this-year`, `-previous-year`
- location: `location-flights-today`, `-yesterday`, `-this-year`, `-previous-year`

Each canned `:type` maps to a **derived date-range filter** (the controller's
date math, `FlightReportsController.js`) with defaults `GliderFlights=true,
MotorFlights=true, TowFlights=false`, page size 1000. The spec asserts the
filter-criteria panel reflects the derived range for at least the 30-day and
this-year cases (pick windows robust to wall-clock drift; the seed inserts flights
with current-time so a year-wide window reliably contains them — see the legacy
stub `e2e/tests/reporting/flight-reports.spec.ts` rationale).

**Results** (`flightreportresults.html`): a **summary table** (GroupBy, TotalStarts,
TotalLdgs, TotalFlights, TotalFlightDuration — `FlightReportSummary.cs:6-37`) +
a **flights data table** (FlightDate, Immatriculation, PilotName, SecondCrewName,
IsSoloFlight, FlightTypeName, StartLocation, LdgLocation, StartDateTime, LdgDateTime,
FlightDuration, FlightComment, with a nested **TowFlight** block —
`FlightReportDataRecord.cs:9-84`, `TowFlightReportDataRecord.cs:8-60`). Summary
**grouping rule** (parity-critical, `FlightReportService.cs`): person reports group
by crew function (Pilot(Glider)/Copilot/Instructor/InstructorSoloFlights + Total);
location reports group by FlightTypeName. HighCharts is **out of scope** — legacy
hard-disables it (`FlightReportsController.js:13` `$scope.showChart = false`); do
not port a chart.

**Custom builder** (`flightreport-custom-configuration.html`): date range
(From/To), three flight-type checkboxes, and a conditional selector — LocationId
(category=location) or FlightCrewPersonId (category=person). Filter DTO shape
`FlightReportFilterCriteria.cs:6-19`. Apply builds the route's JSON filter and
calls the page endpoint.

**Backend contract** (`FlightReportsController.cs:40-73`, `FlightReportService.cs:28-859`):
- `POST /api/v1/flightreports/page/{pageStart}/{pageSize}` → `FlightReportResult`
  (items + summaries), tenant-scoped.
- `POST /api/v1/flightreports/export/excel/{pageStart}/{pageSize}` → XLSX bytes.
  EPPlus sheet "Flights": title row, metadata row, header row, data rows; ~30
  columns including the parallel TowFlight block (`FlightReportService.cs:743-859`).
  Port the **column order + value formatting** exactly (time UTC HH:MM, duration
  [H]:MM, IsSoloFlight 0/1) — that's what the parity harness checks.

**Tenancy** (parity-critical, `BaseService.cs:43-53`, `FlightReportService.cs:116-124`):
every report query filters by the caller's ClubId; a cross-club location filter must
not leak the other club's flights. Drive this with a real low-privilege principal in
the real-idp parity spec ([[project_real_idp_real_roles_catches_authz_gaps]]) — a
pilot should see own/club flights, not another club's.

**Excel parity harness** (S-096): a cell-by-cell XLSX diff tolerant of cosmetic
differences (font, exact column width) but strict on values/types/formulas, wired
into CI against the legacy FlightReports fixture (S-093 inventory). At J-7 it covers
**FlightReports only**; DeliveryMailExport + AircraftStatisticReport are added when
J-10 ships those exports.

## Notes

**Design reference.** There is **no dedicated `screens-reporting.jsx`** in
`docs/modernization/design-reference/`. The closest visual oracle is
`screens-logbook.jsx` — the flights **table/card density**, the page header with an
**"Export" button in `af-page__actions`** (`screens-logbook.jsx:45-50`), and the
pagination footer. Build the reporting results table to that idiom and **reuse the
J-2 flights-list table component** (`alpenflight/web/src/app/features/flights/list/`)
where practical rather than a second bespoke table. Empty/loading/error states:
`screens-misc.jsx`.

**Migration shape.** None — read-side over already-migrated J-2 data. The journey
adds no mapper; the real-idp parity spec runs against the J-2 migrated Flight set.

**Headless homing.** Excel sync-export infra lands here (roadmap "Excel export infra
→ J-7, first sync export consumer"). POI not yet a dependency (`alpenflight` has no
`poi-ooxml`) — adding it + the SXSSF streaming helper is part of this journey's 40%.
Per [[feedback_vertical_slices_first]], build the helper to exactly what FlightReports
needs; J-10 extends it for currency/statistics cells.

**Riders to fold in** (from `_BOYSCOUT.md`, `/do-ship` adds to the task list):
- Per-journey **Maintainability panel** on the J-7 proof-gallery page (fallow + PMD/CPD
  + SpotBugs report-emit) — the standing every-journey rider ([[feedback_maintainability_includes_dupes_and_deadcode]], [[feedback_proof_gallery_per_journey_one_bookmark]]).
- e2e `tsc -p` strictness pre-existing errors (boyscout, opportunistic).
- New code only: keep proof captures reaching the **deployed** gallery page
  ([[project_proof_gallery_one_source_per_journey]], J-6b T-22 lesson).

**Seam hints** (non-binding, seam granularity, for `/do-ship` decomposition):
- `FlightReportQueryService` — read-model over the Flight aggregate: paged + filtered
  (date range / flight types / person|location) + summary aggregation (the grouping rule).
- `FlightReportsController` — `/api/v1/flightreports` page + excel-export endpoints (one resource).
- `ExcelExportSupport` — POI/SXSSF helper (`headerRow/dataRow/dateCell/autoSize/streamingWorkbook`) + the `poi-ooxml` build dep.
- Excel **parity harness** — XLSX cell-diff test infra + the legacy FlightReports fixture (S-093); FlightReports-only at J-7.
- web `reporting` feature folder — picker component, results component (reuse flights-list table + a summary table), custom-builder form, export button.
- web report-filter util — canned `:type` → derived date-range (the controller's date math), shared by picker + custom builder.

**Assumptions made.**
1. Read-side journey: no migration mapper; reuses J-2 migrated Flight/FlightCrew. The
   parity spec re-asserts data fidelity through the report, not a fresh ingest.
2. Chart (HighCharts) is out of scope — legacy disables it; matching legacy = no chart.
3. S-096 harness is built journey-once and FlightReports-scoped now; the other two
   exports attach their fixtures at J-10 (harness reused). Filed this scoping explicitly
   so it doesn't read as "all three exports covered."
4. Spec lives under `alpenflight/web/e2e/tests/reporting/` (mock inner loop) + a
   `real-idp/flight-reports-parity.spec.ts` (gate). The `e2e/tests/reporting/*` stubs
   are the legacy-reference oracle, not the AlpenFlight gate spec.

## Parity decisions (from the legacy behavior oracle, 2026-06-09)

The oracle (`legacy-oracle`, agent a3e15e3d) graded each legacy behavior. Load-bearing
calls for the port:

- **CORRECT legacy bugs (documented parity deviations):**
  - **Tenancy hole.** Legacy does NOT scope the page/export query by ClubId except when a
    *foreign* location is selected (`FlightReportService.cs:114-125`); a person-only or
    unknown-type report can leak other clubs' flights. The rewrite scopes EVERY query by
    `@TenantId` (ADR 0008, structural) — the spec asserts cross-tenant isolation (club-B
    flight never appears in club-A's report). Non-negotiable correction.
  - **`Pilot (Motor)` / `Pilot (Towing)` summary rows omit `TotalFlights` → always 0**
    (`FlightReportService.cs:304-314,366-376`), under-counting the `Total`. The rewrite
    sets `TotalFlights` on all summary rows; spec asserts non-zero motor/tow pilot counts.
  - **Unknown `:type`** runs an unfiltered page-of-1 in legacy; rewrite 404s/empties it.
  - **MIME type:** legacy sends `application/vnd.ms-excel` for an `.xlsx` body; rewrite
    sends the correct `…spreadsheetml.sheet` (not cell-content, so parity-harness-neutral).
- **PRESERVE exactly (the parity contract the harness checks):**
  - **Canned date math** incl. the "last-7-days = 8 inclusive days" off-by-one (today−N …
    today) — INTENDED; reproduce `FlightReportsController.js:118-364` exactly.
  - **"Starts" derived from landings** (`NrOfLdgs`/`NrOfLdgsOnStartLocation`), location-branch
    fly-in `NrOfLdgs-1` term — INTENDED; reproduce the formulas.
  - **Excel layout:** sheet `Flights`; A1 `Flights` (font 20); A3 `Excel Erstellt:` + C3
    timestamp `dd.mm.yyyy HH:MM:ss`; header **row 5**; data from **row 6**; the 30-column
    order INCLUDING the **skipped column 17** and the header typo **`LdgTime UCT`** (col 11);
    time cells `HH:MM` (UTC wall-clock, no TZ shift), duration `[H]:MM`, IsSoloFlight `0/1`,
    AirState/ProcessState/StartType as **raw ints**. The harness asserts the cell
    number-format string AND the value, not rendered text (`FlightReportService.cs:743-859`).
    *Decision:* preserve the typo + skipped column (no in-repo consumer found; exact-match is
    what the parity harness requires). Header styling beyond A1 size = cosmetic, harness-ignored.
  - **Pagination:** `pageStart` is a 0-based row offset; default size 100; **cap 500**
    (canned reports request 1000 → clamped). Keep a sane max; paginate beyond 500 rather than
    silently truncate. Default sort `StartDateTime asc, Immatriculation asc`; `FlightDuration`
    sort key → `FlightDurationInSeconds`.
  - **Row shape:** one row per flight; an aerotow glider carries a **nested TowFlight block**;
    the tow ALSO appears as its own row (when TowFlights flag on) with `TowedGliderFlightId`
    back-ref. Person filter counts crew roles {PilotOrStudent=1, CoPilot=2, FlightInstructor=3}
    only (Passenger/Winch/Observer excluded). Instructor vs Instructor(Soloflights) split on
    `IsSoloFlight`.
  - **DEAD, do not port:** HighCharts (`showChart=false`), per-column table text filters (DTO
    ignores them), `#if DEBUG` `C:\temp` export dump.
- **Operator confirm at gate (not blocking the build):** is the Excel export consumed by any
  *external* tool (none found in `flsserver`/`flsweb`)? If yes, the typo/skip preservation is
  mandatory; if no, it's still the safe default (harness requires it). Recorded, not asked now.
- **StartType int parity (T-03 finding).** Legacy carried a per-club DB `StartTypeId` int with
  no fixed enum; the new schema dropped it (`t_start_type` has only `code`), so the read model
  emits an AlpenFlight stable code→int. The **Excel export (T-07) MUST map the AlpenFlight
  start-type code → the legacy `AircraftStartType` int** {Towing=1, Winch=2, Self=3, External=4,
  Motor=5} for the StartType column to byte-match the legacy fixture; the parity harness (T-08)
  treats StartType under that mapping. AirState/ProcessState already emit legacy SMALLINT codes
  (byte-match directly).

## Boyscout riders — folded vs deferred (operator: "do the boyscout tasks", 2026-06-09)

Per the boyscout scoping rule ("each rides the next touch of its form/surface"), J-7 folds the
riders that touch ITS surface or ride ITS gate; the per-form validation riders ride their own
form's next-touch journey (folding them here would violate the recorded operator design).

**FOLDED into J-7 tasks:**
- Gallery: **structural post-deploy proof-gallery guard** + **shots-present guard** + the
  index-regression fix (J-6 retro + J-5 retro + J-6 "paired per-push") → **T-12**.
- CI: **fail-aggregate** (surface all reds in one run, J-5 retro) → **T-13**.
- **orval `operationId`** stability (J-3 rider) — J-7 ADDS endpoints → renumbers `getN`; set
  explicit operationIds on the new flightreports endpoints (≥ those) → folded into **T-05**.
- e2e **tsc-strictness** opportunistic cleanup (J-2 rider) → **T-14**.
- New custom-builder form built to the **as-you-type bar** (J-6b `liveFieldErrors`) + the
  reporting page kept **low-CRAP** (don't replicate the `*-edit.page.ts` form-mapping/errorPatch
  complexity) → baked into **T-11** (not a separate rider).
- Maintainability panel already SHIPPED (J-6 T-14) — J-7 page inherits it; no task.

**DEFERRED (ride their own next-touch journey — surfaced for the operator):**
- P0/P1/P2/P3/P4 per-form validation riders for persons/flight-types/clubs/profile/aircraft/
  article/location/planning-setup/user edit + flight-EDIT dead `FlightValidator` — J-7 is
  read-side and touches none of those edit forms.
- "Un-mask migration-ingest constraint" — J-7 has no mapper/ingest (read-side); rides a
  migration journey's gate.
- clubadmin4/V29 removal (Flyway-checksum risk), legacy /profile video staging, JIT-username
  robustness, op-field-mutate test — not J-7's surface.

## Tasks

- [x] **T-01 — spec stub + proof-page scaffold (standing).** Author the Playwright spec
  structure/selectors/flow for `/flightreports` (picker → canned results → custom builder →
  export) with thin assertions (commits the screen shape); scaffold the per-journey J-7
  gallery page + link from the persistent index. Capture-legacy-once: commit legacy reference
  shots under `e2e/legacy-reference/reporting/`. *(seam: reporting spec skeleton + gallery page)*
  <br>DONE: mock-auth specs `alpenflight/web/e2e/tests/reporting/{flight-reports,custom-builder}.spec.ts`
  (testid-contract manifests pass today; picker→canned→export→empty + custom-builder flows are
  `test.fixme` until T-09/10/11) + real-idp skeleton `…/real-idp/flight-reports-parity.spec.ts`
  (proof-anchor passes + emits `proof-journey: J-7` → the per-journey J-7 gallery page is generated
  by the existing data-driven generators; J-7 already in the roadmap/index). Legacy capture spec
  `e2e/tests/reporting/reporting-parity-J7.spec.ts` authored against real flsweb selectors + staged
  at the fanout gate; `e2e/legacy-reference/reporting/` refs deferred (legacy stack unrunnable on the
  Alpine/musl dev box — note in `…/legacy-reference/reporting/PENDING.md` + provenance row).
  CI `add_pair` wiring for the reporting refs rides T-12 (structural gallery guard).
- [x] **T-02 — scope gate to J-7; prior journeys → mock-IdP (standing).** Set `mock_test:`
  + `parity_test:` derivation so per-push runs only J-7's own specs heavy (real-idp) and prior
  journeys mock-IdP. *(seam: ci.yml spec selection + J-7 frontmatter)*
  <br>DONE: no ci.yml edit needed — the derive mechanism (J-5 T-14 "Derive journey proof spec"
  + J-6 T-02b "Derive journey mock-e2e filter") is fully frontmatter-driven and already handles
  J-7. Two frontmatter corrections only: (1) reordered `parity_test:` so the real-idp spec
  `e2e/tests/real-idp/flight-reports-parity.spec.ts` is the FIRST token (the derive step reads
  only the first token; previously `flight-reports.spec.ts` (mock) was first → `alpenflight-proof`
  fell back to the J-0 baseline); (2) added `mock_test: alpenflight/web/e2e/tests/reporting/`
  (was absent → `alpenflight-mock-e2e` ran the FULL chromium suite). Simulated both derive steps
  against the J-7 file on `integration/J-7`: `alpenflight-proof` now selects
  `flight-reports-parity.spec.ts` (--project=real-idp, baseline=false); `alpenflight-mock-e2e`
  selects filter `e2e/tests/reporting/` → the two J-7 reporting specs only (--project=chromium).
  Fail-safe intact (non-integration branch → baseline / full suite). Prior journeys already run
  mock-IdP per-push via this mechanism (their branches aren't under work); full cross-journey
  real-idp regression stays nightly + the §4 do-ship gate.
- [x] **T-03 — backend FlightReport read model: paged filtered query + DTOs.** New read-side
  query over the Flight aggregate (date-range / type-flags / person|location filter), **tenant-
  scoped (ADR 0008)**; DTOs `FlightReportResult` + `FlightReportDataRecord` + nested
  `TowFlightReportDataRecord` (row shape per oracle §4); pagination (0-based offset, cap 500,
  default sort). No summary yet. *(seam: FlightReportQueryService + report DTOs + repo query)*
  <br>DONE: `FlightReportQueryService` (application) + `FlightReportDtos` (FlightReportResult /
  FlightReportDataRecord / TowFlightReportDataRecord / FlightReportFilter / present-but-empty
  FlightReportSummary) + `FlightCategory` (domain) + `FlightReportRepository` port (domain) +
  `JpaFlightReportRepository` (infra, native SQL). Tenant-scoped on EVERY path: explicit
  `f.operating_club_id = :tenant` on page + count (corrects the legacy tenancy hole); decoration
  joins (aircraft/person/location/flight-type) deliberately unscoped so cross-tenant ride-throughs
  (charter aircraft S-058, cross-tenant crew Person S-051, cross-club location report) resolve —
  registered in `database/native-sql-register.md` (`flight-report-read-model`). Pagination: 0-based
  offset, default size 100, hard cap 500, default sort `start_date_time asc, immatriculation asc`,
  `FlightDuration` sort-key → epoch-seconds remap. Air-state/duration/category computed in Java
  (never stored); AirState/ProcessState emitted as legacy SMALLINT codes. Aerotow glider carries a
  nested TowFlight block; the tow appears as its own row with `towedGliderFlightId` back-ref. IT
  `FlightReportQueryServiceIT` (4 tests, real-Postgres): filtered rows + decorations + duration;
  tenant isolation (club-B never returned to club-A); aerotow nested-tow shape; person-filter
  role inclusion. `./gradlew check` green (cpdRatchet/pmdMain/arch-guards/OpenApiSnapshotIT/IT).
  NOTE (parity): `StartType` int is an AlpenFlight stable code→int (V2 seed order) — legacy carried
  a per-club DB int with no fixed enum and the new schema dropped it; documented in the service.
- [x] **T-04 — backend summary aggregation.** Person-branch 6 rows (Pilot Glider/Motor/Towing,
  Copilot, Instructor, Instructor-Solo, Total) + location-branch group-by-FlightTypeName + Total;
  starts-from-landings formulas; **correct the `TotalFlights=0` legacy bug** on all rows.
  `FlightReportSummary` DTO. *(seam: FlightReportQueryService summary computation + DTO)*
  <br>DONE: thickened `FlightReportSummary` (groupBy/totalStarts/totalLdgs/totalFlights/
  totalFlightDuration) + `FlightReportRepository.SummaryRow` projection + `findSummaryRows`
  (one tenant-scoped pass, no pagination; shares the page query's `appendWhere`/`bindWhere` via
  new `prepare(...)` helper — keeps cpdRatchet at baseline). Service `computeSummaries` folds the
  rows in memory: person branch = 6 fixed-order rows (each present only with ≥1 flight) + Total,
  reproducing the legacy starts-from-landings person formula INCLUDING the intended quirk
  (`totalStarts` base = `nrOfLdgs`, noStart fallback) `FlightReportService.cs:244-251`; location
  branch = group-by-FlightTypeName (alphabetical) + Total with the 4-term same-airfield/fly-in
  (`nrOfLdgs-1`)/fly-out/outlandings starts formula `:683-691`. **Corrected the legacy
  `TotalFlights=0` bug** — `totalFlights` set on ALL rows (legacy omits it on Pilot Motor/Towing
  `:304-314,366-376`). Tenant-scoped on every path (same `operating_club_id = :tenant` predicate;
  native-sql-register updated). All required fields present on the migrated Flight aggregate
  (`nr_of_ldgs`, `nr_of_ldgs_on_start_location`, `no_start_time_information`,
  `no_ldg_time_information`, `start_location_id`, `ldg_location_id`, `is_solo_flight`) — no
  escalation. 3 new ITs (person-branch grouping + corrected non-zero Motor/Towing + Instructor/
  Solo split; location group-by-FlightTypeName + Total; tenant-scoped). `./gradlew check` green.
- [x] **T-05 — backend FlightReportsController.** `POST /api/v1/flightreports/page/{start}/{size}`
  → result+summaries; exception handling; **explicit `operationId`s** (orval stability rider);
  ITs incl. tenant-isolation. *(seam: FlightReportsController + IT)*
  <br>DONE: `FlightReportsController` (package-private, mirrors `FlightsController`) exposes
  `POST /api/v1/flightreports/page/{pageStart}/{pageSize}` → `FlightReportResult` (items+summaries).
  Body DTOs `FlightReportPageRequest{sorting, searchFilter}` + `FlightReportSearchFilter` (mirrors
  legacy `FlightReportFilterCriteria`: FlightDate From/To, FlightCrewPersonId, LocationId,
  Glider/Motor/Tow flags) with oracle §8.45 defaults applied when fields omitted (`@Nullable Boolean`
  → glider/motor true, tow false). 0-based offset, default size 100, cap 500 (query service enforces,
  controller passes through). Sorting honours only `FlightDuration: asc|desc` → the service's
  `sortByDuration` remap; other keys → default sort. Read-shaped POST → `@ReadOnlyQuery` (exempts the
  ControllerAuditCoverage mutating-verb guard; no audit event). Explicit
  `@Operation(operationId="getFlightReportPage")` → stable named orval method (J-3 rider); OpenAPI
  snapshot regenerated (`./gradlew generateOpenApiSnapshot`; +298 lines incl. the new operationId).
  **Authz:** mirrors `FlightsController.list` post-J-3 — `hasAnyRole('CLUB_ADMINISTRATOR',
  'FLIGHT_OPERATOR', 'PILOT')` (a PILOT reads own/club reports — the J-3 PILOT-403 lesson; reports
  read the same tenant-scoped row set as the flights list). **Escalation/fix-forward (T-03 bug
  surfaced by the wire test):** `FlightReportQueryService` read the tenant from `TenantContextCarrier`
  directly, which is empty on the real HTTP path (only set by test `runAs`/`@WithTenant`) → every real
  request 500'd "No tenant in context". Corrected to inject `ClubTenantIdentifierResolver` and call
  `resolveCurrentTenantIdentifier()` (the canonical native-SQL tenant pattern — `PlanningDaysService`,
  resolver checks `TenantContextCarrier` first so the T-03/T-04 `runAs` ITs stay green). 3 ITs
  (`FlightReportsControllerIT`): club-admin happy page (items+summaries+Total); club-A filtering by
  a club-B location sees no club-B rows (tenant isolation); PILOT-role caller reads. No exception
  handler needed (no new domain exception types; the global handlers cover it). `./gradlew check`
  green (cpdRatchet/pmdMain/arch-guards incl. ControllerAuditCoverage/OpenApiSnapshotIT/all ITs).
- [x] **T-06 — ExcelExportSupport POI helper (S-094).** Add `poi-ooxml` dep; SXSSF streaming
  helper: `headerRow/dataRow/dateCell/timeCell/durationCell/intCell/streamingWorkbook` matching
  legacy formats; one unit test per helper. *(seam: ch.alpenflight.excel.ExcelExportSupport + dep)*
  <br>DONE: `org.apache.poi:poi-ooxml:5.5.1` added to `alpenflight/server` (latest stable, runs on
  JDK 25; `poi-ooxml-full` NOT needed — streaming write path only touches the lite ooxml-schemas).
  POI's transitive `commons-io:2.21.0` clashed with the existing `commons-compress`-pulled `2.16.1`
  under `failOnVersionConflict()` → pinned `commons-io:2.21.0` explicitly (one resolved version).
  Helper homed at **`ch.alpenflight.platform.excel.ExcelExportSupport`** (NOT a new top-level
  `ch.alpenflight.excel` module — `platform` is the OPEN Spring-Modulith shared kernel every feature
  may import without a named interface; mirrors the `platform.text.FreeText` precedent). Backed by
  `SXSSFWorkbook(100)` (default row window, S-094). Helpers: `streamingWorkbook()`/`workbook()`,
  `titleCell(...,fontSizeInPoints)` (A1 "Flights" font 20), `headerRow`, `dataRow`, `stringCell`,
  `intCell` (raw ints — AirState/ProcessState/StartType/IsSoloFlight), `dateCell(value,formatString)`,
  `timeCell` (`HH:MM`), `durationCell` (`[H]:MM`, value = seconds/86400 fraction-of-day), generic
  `formattedCell(value,formatString)` (the J-10 currency/locale extension seam), `autoSize` +
  `trackColumnsForAutoSizing` (SXSSF requires tracking BEFORE row writes — documented + autoSize
  defensively enables all-column tracking). Per-format-string + per-font-size `CellStyle` caching
  (POI 64k-style cap). 13 unit tests (one per helper + style-reuse + autosize), plain JUnit5/AssertJ
  (no Postgres/Spring) — write cell → serialize SXSSF → read back as XSSF → assert value AND
  number-format string. Currency/locale cells deferred to J-10 (noted in javadoc) per
  vertical-slices-first. `./gradlew check` green (arch-guards/PMD/cpdRatchet at baseline 4767/
  OpenApiSnapshotIT/all ITs). 4 files: build.gradle.kts + ExcelExportSupport.java + package-info.java
  + ExcelExportSupportTest.java.
- [x] **T-07 — flight-reports Excel export endpoint (S-095).** `POST …/export/excel/{start}/{size}`
  streaming `.xlsx`; exact 30-col layout + A1/A3/C3 metadata + skipped col 17 + `HH:MM`/`[H]:MM`
  formats per oracle §5; correct MIME. *(seam: FlightReportsController export endpoint + writer)*
  <br>DONE: `POST /api/v1/flightreports/export/excel/{pageStart}/{pageSize}` on
  `FlightReportsController` — same `{sorting, searchFilter}` body, tenant scoping, and authz
  (`CLUB_ADMINISTRATOR`/`FLIGHT_OPERATOR`/`PILOT`) as the page endpoint; `@ReadOnlyQuery`
  (read-shaped, no audit). Returns `ResponseEntity<StreamingResponseBody>` — the SXSSF workbook is
  streamed straight to the response output stream (no whole-file buffering), reusing the T-06
  `ExcelExportSupport` helper. `Content-Type` = corrected OOXML spreadsheet MIME (legacy sent the
  wrong `application/vnd.ms-excel` — documented harness-neutral deviation); `Content-Disposition:
  attachment; filename="FlightReports.xlsx"`. Stable `@Operation(operationId="exportFlightReportExcel")`
  (orval) — OpenAPI snapshot regenerated (+46 lines, the new path+operationId). The shared
  request→result preamble extracted to a private `runReport(...)` so the two endpoints don't duplicate
  (kept cpdRatchet at baseline). **Layout** lives in new `FlightReportExcelWriter` (web package, the
  presentation seam): sheet `Flights`; A1 `Flights` font 20; row 2 blank; A3 `Excel Erstellt:` + C3
  timestamp `dd.mm.yyyy HH:MM:ss`; header row 5 / data row 6; the 30-column legacy order INCLUDING the
  intentionally-blank column 17 (no header, no cell) and the preserved typo `LdgTime UCT` (col 11);
  tow columns 19-30 written only when the row carries a TowFlight; time cells = UTC wall-clock `HH:MM`
  (Instant→LocalDateTime at ZoneOffset.UTC, no TZ shift); duration `[H]:MM`; IsSoloFlight int 1/0;
  StartType already mapped to the legacy `AircraftStartType` int by the query service (T-03);
  AirState/ProcessState raw ints; FlightDate (col 2) carries NO number format (legacy default). 1 new
  IT (`FlightReportsControllerIT.exportExcel_streamsXlsxWithLegacyLayout`): POSTs the export, reads the
  `.xlsx` back with POI, asserts MIME+Content-Disposition, A1/A3/C3 metadata (incl. C3 format string),
  the row-5 header (incl. `LdgTime UCT` typo + blank col 17), and a data row's key cells (StartTime/
  LdgTime `HH:MM` format, FlightDuration `[H]:MM` format, IsSoloFlight 0, StartType WINCH_LAUNCH→1).
  `./gradlew check` green (cpdRatchet baseline/pmdMain/arch-guards incl. ControllerAuditCoverage/
  OpenApiSnapshotIT/all ITs). No escalation — the AlpenFlight read model reproduces every targeted
  legacy cell value (StartType via the documented code→int map).
- [x] **T-08 — Excel parity harness (S-096).** XLSX cell-by-cell diff (value+number-format,
  tolerant of font/width); legacy FlightReports fixture (S-093 inventory + committed fixture);
  CI-wired, FlightReports-scoped (J-10 adds the other two exports). *(seam: excel-parity test harness + fixture)*
  <br>DONE: reusable comparator `ch.alpenflight.platform.excel.ExcelParityComparator` (test scope) —
  reads two `.xlsx`, STRICT on sheet names + cell type + value + number-format string, TOLERANT of all
  cosmetic style (font/size/bold/fill/border/width — it only ever reads type+value+data-format, so
  cosmetic drift is structurally invisible); `Diff.describe()` lists each mismatch as
  `Sheet!A1: <reason> expected=… actual=…`. Self-test `ExcelParityComparatorTest` (7 cases) proves
  strict-on-format/value/type + tolerant-of-cosmetic (guards against a false-green over-lenient
  comparator). **Golden fixture** `src/test/resources/excel-parity/flight-reports-legacy-golden.xlsx`
  built by `FlightReportGoldenFixture` (the contract-in-code, hand-built from the S-093 inventory +
  oracle §5 — deliberately NOT via the production writer, else tautological) over a fixed
  `FlightReportGoldenDataset` (plain-glider row + aerotow-glider row with nested tow block;
  HH:MM/[H]:MM/IsSoloFlight 0-1/StartType WINCH=2 & AEROTOW=1/raw AirState-ProcessState ints; tow
  duration 25h to exercise `[H]`). `FlightReportGoldenFixtureTest` guards the committed bytes against
  the generator (no silent drift); **`FlightReportExcelParityIT`** runs the production
  `FlightReportExcelWriter` over the SAME dataset and asserts cell-parity-equal to the golden fixture.
  Regen seam: `./gradlew generateFlightReportGoldenFixture` (JavaExec, test classpath). **CI-wired** —
  all three tests run in `check` via `test` (plain JUnit, no Postgres/Spring needed). `./gradlew check`
  green (cpdRatchet/pmdMain only touch `main` — harness is test-scope; arch-guards/OpenApiSnapshotIT/
  all ITs pass). **PROVENANCE (honest):** the golden fixture is derived from the documented
  S-093/oracle contract, NOT a live legacy export — the Mono/MSSQL legacy stack is unrunnable on this
  Alpine/musl box (mirrors T-01's deferred legacy screenshots). The harness proves OUR writer matches
  the DOCUMENTED contract; the live-legacy byte-match is a fanout-gate concern (a live-legacy fixture
  swaps in here when the fan-out brings up the legacy stack). **SCOPE:** FlightReports ONLY;
  DeliveryMailExport + AircraftStatisticReport ride J-10 (comparator reused, not rebuilt) — recorded
  in `src/test/resources/excel-parity/README.md` so it doesn't read as "all three covered".
- [x] **T-09 — web reporting scaffold: feature folder + picker + date-math util + store.** Routes
  (`/flightreports`, `/:category/:type`, `/custom/:category/:filter/edit|:mode`); picker tile grid
  (person + location categories); canned `:type` → derived date-range util (oracle §1); report
  store + orval client wiring. *(seam: web reporting feature scaffold + picker)*
  <br>DONE: orval regenerated → new `flightreports/flightreports.service.ts` with NAMED methods
  `getFlightReportPage` + `exportFlightReportExcel` (explicit operationIds from T-05/T-07 held;
  no positional `getN`) + 8 additive model types; clean additive diff (no renumbered existing
  methods). Feature folder `src/app/features/reporting/`: `reporting.routes.ts` (lazy, registered
  in `app.routes.ts` as `/flightreports`; `custom/...` routes ordered before `:category/:type` so
  the literal `custom` segment isn't swallowed by `:category`); `picker/flight-reports-picker.page.ts`
  (functional tile grid — person tiles my-flights-today…previous-year, location tiles location-flights
  today/yesterday/this-year/previous-year; testids `flightreports-category-{person,location}` +
  `flightreports-tile-<category>-<type>` per the T-01 contract; logbook page-header idiom, ng-zorro +
  Tailwind, RouterLink anchors); `report-placeholder.page.ts` (scaffold the `:category/:type` results
  route + the two `custom/...` builder routes for T-10/T-11 to fill — page chrome + spinner, no
  results table/form built here); `canned-report.ts` (PURE date-math util `cannedDateRange`/
  `cannedReportSpec` reproducing `FlightReportsController.js:118-364` EXACTLY incl. the intended
  off-by-one: last-7-days = today−7…today = 8 inclusive days, same for 30-days/12-months/24-months;
  this-year = Jan 1…today; previous-year = last Jan 1…last Dec 31; default flags glider+motor on, tow
  off per the journey note — legacy actually sets tow on, corrected per § Parity decisions);
  `canned-report-request.ts` (composes `{searchFilter}` binding person→flightCrewPersonId /
  location→locationId); `report.store.ts` (signalStore over `getFlightReportPage` with
  `{sorting,searchFilter}` → items+summaries+totalRows, loading/error, isEmpty; clears on tenant
  switch/logout). 18 unit tests (canned-report 13 + canned-report-request 5) green. `pnpm lint` +
  `ng build` green; date-math + request-composer unit tests green.
  <br>ESCALATION (source of canned ids): person canned reports source `flightCrewPersonId` from
  `SessionStore.authenticatedUser().personId` (populated by `/me`, S-165) — works end-to-end.
  **Club homebase location id for LOCATION canned reports is NOT on the web wire** — `ClubResponse`
  has no `homebaseId` and `/me` (MeResponse) carries no homebase field. `cannedReportRequest` leaves
  `locationId` null for location reports until that seam exists; the backend is tenant-scoped (ADR
  0008) so a location report with no locationId still returns the caller's club (club-wide, not
  homebase-filtered). FLAGGED for T-10 / a follow-up: add `homebaseLocationId` to `/me` or
  `ClubResponse` so location canned reports filter to the homebase.
- [x] **T-09b — expose club homebase on `/me` + wire location reports (T-09 gap).** `t_club` has
  `homebase_id` (V3 `fk_club_homebase_id`); add `homebaseLocationId` to `MeResponse` (the club
  context lives on `/me`) + regen orval + wire `cannedReportRequest` to pass `locationId` for
  LOCATION canned reports. **Required, not optional:** the backend location-branch summary only
  computes when a LocationId is set — without it a location report has an EMPTY summary, breaking the
  AC "location report summary groups by FlightTypeName." *(seam: MeResponse homebase field + web canned-request wiring)*
  <br>DONE: **Backend** — `MeService` SELECT now `LEFT JOIN t_club c ON c.id = u.club_id` and
  projects `c.homebase_id` (nullable: a club may have no homebase; null when no club). `MeView` +
  `MeResponse` carry `homebaseLocationId` (UUID → `loc-<uuid>` external form via `LocationId`, ADR
  0019 — matches `FlightReportSearchFilter.locationId` so the SPA passes it straight through). DTO ≠
  entity preserved (projection through MeView). No new native-SQL-register entry needed — `t_club`
  /`t_user` aren't `@TenantId`-scoped (MeService is principal-scoped, already unregistered; verified
  by green `NativeSqlRegisterTest`). OpenAPI snapshot regenerated (`generateOpenApiSnapshot`,
  +`homebaseLocationId` on `MeResponse`). `MeControllerIT`: +1 test (club with homebase → `loc-`
  prefixed id) + null assertion on the no-person test. **Web** — orval regenerated (clean additive
  diff: only `meResponse.ts` gained `homebaseLocationId?`). Hand-written `MeResponse` (me.service.ts)
  + `User` (session.store.ts) + `oidc-claims.ts` mapper + `app.config.mock.ts` mock principal (set to
  the Bern-Belp seed homebase) all carry the field; `SessionStore.loadMe` patches it from `/me`.
  `cannedReportRequest` ALREADY accepted `homebaseLocationId` and wired LOCATION reports →
  `searchFilter.locationId` (built in T-09); the 5 canned-report-request unit tests already assert
  location reports pass `locationId` (now reachable end-to-end). Stale T-09 escalation comment
  removed. `./gradlew check` green; `ng lint` + `ng build` + affected vitest (81 tests, incl. updated
  `oidc-claims.spec` + the 6 User-literal fixtures) green.
- [x] **T-10 — web reporting results page.** Summary table + flights table (reuse J-2 flights-list
  table idiom; nested tow rendering) + **Excel export button** (streamed download); empty-state.
  *(seam: web reporting results component)*
  <br>DONE: `results/report-results.page.ts` replaces the placeholder on
  `/flightreports/:category/:type` (route rewired; placeholder kept for the two T-11 `custom/...`
  shells). Reads `:category` + `:type` via `withComponentInputBinding` `input()`s, derives the
  filter via the T-09 `cannedReportRequest` (binding `SessionStore.authenticatedUser()` personId /
  homebaseLocationId), and loads `ReportStore` from an `effect()`. **(1) Filter-criteria panel**
  (`report-filter-criteria`): derived From–To via `formatIsoDateDdMmYyyy` (DD.MM.YYYY, J-6b date
  convention), flight-type label (Glider/Motor/Tow, off→omitted), and person/location scope.
  **(2) Summary table** (`report-summary-table`/`-row`): GroupBy/Starts/Ldgs/Flights/Duration; renders
  the backend's crew-function (person) or FlightTypeName (location) rows + Total straight through.
  **(3) Flights table** (`report-flights-table`/row `report-flights-row`): logbook column idiom —
  FlightDate/Immat/Pilot/2ndCrew/Solo/Type/From/To/Takeoff/Landing/Duration/Comment; **nested tow**
  rendered as a `report-flights-tow-row` sub-row (↳ Tow + immat/pilot/type/locations/times) under an
  aerotow glider. Dates DD.MM.YYYY, times HH:MM. **(4) Excel export** (`report-excel-export`, in the
  page-header actions slot): new `ReportStore.exportExcel()` fetches the streamed `.xlsx` via the orval
  `exportFlightReportExcel` with `responseType:'blob' + observe:'response'` (parses the
  Content-Disposition filename); the component does the `URL.createObjectURL`→anchor-click download
  (handshake-page idiom — HTTP in store, DOM in component). **(5) Empty-state** (`report-empty`,
  screens-misc copy) when `store.isEmpty()`; export button disabled while empty. Kept low-CRAP (no
  reactive-forms mapping/errorPatch — read-side derived filter). `pnpm` eslint + prettier clean over
  the reporting + spec globs; `ng build` green; reporting vitest 18 green. **Un-fixmed the 5 now-
  implementable T-01 mock cases** (picker + canned-person-results + location + Excel-export + empty-
  state) in `e2e/tests/reporting/flight-reports.spec.ts` — added an export-endpoint `page.route` stub
  (attachment .xlsx) — all 7 reporting mock specs green (1 still-fixme = T-11 custom-builder flow).
- [x] **T-11 — web custom report builder form.** Date range + 3 flight-type toggles + conditional
  person/location selector; built to the **as-you-type bar** (debounced `liveFieldErrors`), kept
  **low-CRAP**. *(seam: web custom-builder form component)*
  <br>DONE: `edit/report-custom-builder.page.ts` on `/flightreports/custom/:category/:filter/edit` —
  typed reactive `FormGroup` (From/To date pickers via `af-input type=date`, three flight-type
  checkboxes Glider/Motor/Tow defaulting on/on/off per § Parity decisions, and a CONDITIONAL selector:
  `af-select` location picker when `:category==='location'`, person picker when `'person'`). Apply →
  pure `formToFilter` → `encodeCustomFilter` (JSON+`encodeURIComponent`) → `navigateByUrl`
  `custom/:category/<encoded>/apply`; the **results page** (T-10) now reads BOTH routes off the
  paramMap and on the custom-apply route `decodeCustomFilter`s the `:filter` segment → renders the
  filtered set. **Filter round-trips through the route param (the AC)** — the un-fixme'd
  custom-builder mock e2e asserts the encoded segment carries From/To/towFlights AND the summary +
  flights tables render. **As-you-type bar (boyscout):** From/To required via the J-6b debounced
  `liveFieldErrors` (`fromErrors`/`toErrors`) bound on `af-form-field [errors]`, not the touched-only
  wiring. **Low-CRAP:** the whole mapping is one small pure `formToFilter` — NO
  `formToUpdateRequest`/`errorPatch` cascade (it's a filter form, not entity CRUD). **Selector data:**
  reused the generated `PersonsService.listPersons` / `LocationsService.listLocations` via two
  idempotent lazy loaders on `ReportStore` (store owns the HTTP — §4; the mock spec stubs
  `/api/v1/persons` + `/api/v1/locations`). Codec is a pure framework-free pair with 10 unit tests
  (`custom-filter.spec.ts`: round-trip, malformed→null, `{}`-default, formToFilter category routing);
  decode is encoding-tolerant (paramMap pre-decodes once). Removed the now-dead
  `report-placeholder.page.ts` (both custom shells became real pages — dead-code/maintainability).
  `pnpm lint` + reporting vitest (28) + `ng build` green; all 8 reporting chromium mock e2e specs
  pass (incl. the now-implemented custom-builder flow).
- [x] **T-12 — boyscout: structural post-deploy gallery guard.** Post-deploy job asserts the J-7
  bookmark row is a LIVE LINK and every declared asset (videos + paired shots) resolves 200 on the
  DEPLOYED page; add the shots-present pre-deploy guard. *(seam: deployed-gallery-guard step + add_shot presence guard)*
  <br>DONE: STRUCTURAL (CI steps + a browserless spec that runs every proof deploy — not a procedure
  rule), per the operator grill ("a procedure rule kept failing, so this must be structural"). Three
  guards, all in the existing `proof-gallery-links` Playwright project (browserless, runs in any CI
  context): (1) **`[deployed-journey]` POST-deploy guard** — against the LIVE deployed URLs, asserts the
  journey-under-work's bookmark row on the persistent previews index is a LIVE LINK (200, anchor form —
  NOT a `pending` placeholder span) AND its per-journey page declares ≥1 asset with EVERY video/screenshot
  resolving 200 (polls ~60s for gh-pages). This is the catch the generator unit test can't make: it shipped
  wrong ~4× (green generator while the deployed bookmark read `pending`/the page was thin — deploy-path and
  probe-path drift independently). Wired into ci.yml's `alpenflight-proof` job (the gap: the existing live
  link-check only ran the J-0 BASELINE caption path; a journey-under-work run had NO deployed assertion —
  this step gates the `proof_is_baseline != 'true'` path) AND folded into the fanout's existing T-33
  deployed link-check (broadened grep `\[deployed` + `GALLERY_DEPLOYED_JOURNEY`). (2) **`[shots-present]`
  PRE-deploy guard** — `add_shot`/`add_pair` silently drop a missing PNG; this reads the new committed
  `expected-shots.json` contract + the staged `screenshots.json` and FAILS if any `expected` `<side>:<view>`
  for the journey-under-work is absent (a `pending`/deferred shot is tolerated + surfaced) — replacing the
  silent drop. Runs `always()` + gallery-built-gated BEFORE deploy in BOTH ci.yml (per-push) and the fanout.
  (3) **J-7 reporting pairing DECLARED now** — added the J-7 `add_pair` (ci.yml per-push) + `add_shot`
  (fanout) for the picker/result/custom views matching `e2e/legacy-reference/reporting/PENDING.md`; the six
  J-7 views sit in `expected-shots.json` `pending` (legacy → fanout-deferred; AlpenFlight → T-15 thickens
  the parity spec to write the PNGs, which flips the AF three to `expected`) so a future missing-shot reds
  rather than silently passes, without redding the gate now. 4 files: new
  `e2e/proof-gallery/expected-shots.json` + the spec + ci.yml + the fanout. `required` aggregator semantics
  intact (new steps skip→success; deploy/guard steps gated on `!cancelled()`/`always()` so a red case still
  deploys+guards the gallery). VERIFIED LOCALLY: actionlint clean on both workflows; the spec project green
  (4 tests — `[happy]` + 3 env-gated); `[shots-present]` proven across PASS (J-1 full fixtures), FAIL
  (missing `legacy:list` → precise red), pending-tolerated (J-7), and skip (un-guarded J-0); `[deployed-
  journey]` proven PASS against a real HTTP-served local gallery (J-1 live bookmark + page assets 200) and
  the J-7 pending-row regex confirmed against the served index HTML. DEFERRED-TO-REAL-RUN (no gh-pages
  deploy in a worker): the live ci.yml/fanout deployed steps fetching the ACTUAL gh-pages URLs — only a real
  proof run confirms the end-to-end gh-pages timing/path; logic validated by reasoning + actionlint + the
  local HTTP-served exercise. Pre-existing e2e lint (`let galleries`/`let broken` no-useless-assignment) +
  the legacy capture PNGs left to their owners (T-14 / fanout) — not this task's surface.
- [x] **T-13 — boyscout: CI fail-aggregate.** Run the independent checks (build / server-test /
  web-lint / mock-e2e) so one run reports every red at once instead of stopping at the first.
  *(seam: ci.yml job parallelism/aggregation)*
  <br>DONE: surgical, merge-gate-conservative. The serial-discovery lived INSIDE one `next-build`
  job that built server (Gradle) THEN web (Node) THEN ran `pnpm lint; pnpm format; pnpm test;
  pnpm build` in a single `run:` block — a server-build red hid every web check, a `pnpm lint` red
  hid the test/build reds. SPLIT `next-build` into two PARALLEL toolchain jobs: **`next-build-server`**
  (JDK/Gradle: server + migration-tool builds — DISJOINT toolchain, so the split duplicates NO
  expensive setup) and **`next-build-web`** (Node/pnpm: generate-api drift check → lint → format →
  test → build, each its OWN step gated `${{ !cancelled() && …web=='true' }}` so a red step doesn't
  skip the later independent checks). One run now surfaces server-build, web-lint, web-format,
  web-test, web-build reds at once; `alpenflight-mock-e2e` was already a separate parallel job so it
  reported independently before this. **`required` aggregator preserved exactly:** swapped `next-build`
  → `next-build-server` + `next-build-web` in `needs:` AND the result-check loop (a red in EITHER reds
  `required`; both `skipped` on a docs-only push → the unchanged `success|skipped` path keeps the gate
  green). The aggregator JOB NAME `required` (the branch-protection key) is UNCHANGED. **LEFT CHAINED
  (deliberate, NOT artificial):** `alpenflight-proof → dashboard-proof → profile-proof` stay `needs:`-chained
  — operator-recorded runner-CONTENTION serialisation (full-stack real-chain jobs: Postgres+Keycloak+
  Mailpit+backend+ng-serve; a cited starved 4th-parallel run caused a cold-start flake). They ALREADY
  report independently via `if: always()` (a red upstream proof does NOT skip the downstream job — it runs
  + reds on its own), so the fail-aggregate property already holds there; breaking the chain would
  re-introduce the contention flake. VERIFIED: actionlint clean (0). DEFERRED-TO-REAL-RUN: only a live
  GitHub Actions run confirms the two split jobs schedule in parallel + the `!cancelled()` step-skip
  semantics end-to-end (no local Actions runner) — validated here by actionlint + job-graph reasoning +
  the unchanged aggregator needs-list/loop. *(seam: ci.yml job parallelism/aggregation)*
- [x] **T-14 — boyscout: e2e tsc-strictness cleanup.** Clear the ~23 pre-existing
  `exactOptionalPropertyTypes`/`maxFailures` errors so an e2e `tsc` gate could be wired. *(seam: e2e/tsconfig strict cleanup)*
  <br>DONE: empirical count was **35** real errors across 9 e2e files (the rider's "~23"
  under-counted — and clearing the two project-level `maxFailures` lines UNMASKED two latent
  config-object errors `workers: undefined` + `webServer: undefined` that the overload failure had
  short-circuited). `tsc -p e2e/tsconfig.json --noEmit` now exits **0**. Fixes are type-level only,
  behavior unchanged (all 220 specs still collect under esbuild). By file: **playwright.config.ts** —
  removed the two per-project `maxFailures` entries (it's a SUITE-level `TestConfig` option, ignored
  per-project → was a runtime no-op; use `--max-failures` for a fail-fast gate); `workers` +
  `webServer` switched to conditional-spread so optional keys are omitted (not set to `undefined`)
  under `exactOptionalPropertyTypes`; `webServer` hoisted to a `const`. **generate-gallery.spec.ts** —
  conditional-spread `journeyUnderWork`; removed dead `VIEWS` const (pre-existing `no-unused-vars`).
  **generate-previews-index.spec.ts** — `by['J-0']/['J-1']` captured + `toBeDefined()` + `?.`;
  `hrefs` filtered to `string[]`. **flights-list.spec.ts** — `allFlights` typed as a 3-tuple so
  `[0..2]` are known-present (`noUncheckedIndexedAccess`). **aircraft-crud.spec.ts** — conditional
  spread for `ownerClubId` carry-through. **handshake.spec.ts** — `copied[0]!` after a length assert.
  **persons-add-modal.spec.ts** — added an `optional(key,value)` helper; optional person/membership
  fields spread in only when defined. **custom-builder.spec.ts** — bracket access for the
  index-signature props (TS4111). **proof-gallery-links.spec.ts** — the two named `no-useless-assignment`
  findings (`let galleries`/`let broken`) fixed by dropping the dead `= []` initializers (both vars are
  unconditionally assigned before use). eslint + prettier clean on all 9 touched files. NOTE for
  /do-retro: an e2e `tsc -p e2e/tsconfig.json --noEmit` CI gate would now be a natural follow-on (the
  tree is green) — out of scope here per the rider. Other pre-existing e2e lint findings (array-type /
  no-empty-pattern / preserve-caught-error in untouched real-idp specs) left to their owners.
- [x] **T-15 — thicken spec to full real assertions (standing final).** Full happy + key-error
  + edge assertions from the oracle (canned date windows, summary grouping incl. corrected
  TotalFlights, nested tow, tenant isolation, Excel parity). *(seam: reporting spec full assertions)*
- [x] **T-16 — gate-revealed fixes (§4).** (a) Web build prettier on `report-custom-builder.page.ts`.
  (b) Real-chain location report had an EMPTY summary: root cause `t_club.homebase_id` was NULL for
  seed-club-1 (no migration set it; the mock principal hardcoded Bern-Belp, hiding the gap — a real
  mock-vs-real divergence). Fix: `V37__dev_seed_club_homebase.sql` sets it to c001 + seed J-7 flights
  there; product wiring (T-09b/T-10) confirmed correct. (c) Fanout `[shots-present]` false-red: modeled
  expected-shots per producing context (`producedBy: proof` for AF reporting shots; guard reads
  `GALLERY_PROOF_CONTEXT`) — a real drop still reds in the proof context. *(seam: V37 seed + reporting fixture + shots-present context model)*

## §4 gate — pre-existing fanout red (NOT a J-7 blocker)

The fanout's **migration parity** specs (J-0c/J-2/J-5/planning) fail with `bundle ingest 500 sqlstate=23505`
— **pre-existing main-red** (main's scheduled fanout is `failure` on 2026-06-08 AND 06-09 with the identical
error). J-7 carries **no mapper** (read-side), so this is not a J-7 regression; it's the known CLUB
identity-pgcopy ↔ `seedClubLegacyIdMap` collision flagged for **pre-J-21** (see `_ORDER.md` J-0b open
follow-ups + the J-6 retro "un-mask the ingest constraint" rider). J-7's PRIMARY required gate is CI
`alpenflight-proof` (real-idp), made green by T-16. **Operator decision pending:** whether J-7's
legacy↔AlpenFlight *paired-capture* half (fanout-deferred legacy reporting shots) blocks merge given the
shared fanout is pre-existing-red — or J-7 merges on the real-idp green with the legacy pairing riding the
fanout fix. The reporting AF captures + the real-chain proof land per-push; only the *legacy* pairing waits.

**Operator decision (2026-06-09): FIX the fanout `23505` inside J-7** — make the full migration fanout
green as part of this journey (so the complete paired-capture done-bar lands + the pre-existing main-red
fanout is repaired). Scope expansion accepted by the operator. Tracked as **T-17** below.

- [x] **T-17 — fix the migration-fanout `23505` ingest collision (operator-directed).** The fanout's bundle
  ingest 500s with `sqlstate=23505` (the constraint name was masked in the error body per the J-6 rider).
  Sub-steps: (a) **un-mask the constraint name** in dev/test ingest errors; (b) root-cause + fix the collision
  so a full real-producer bundle ingests green; (c) confirm the downstream "reservation did not migrate" parity
  reds clear (same root cause or a second one); (d) green fanout run.
  *(seam: MigrationBundleIngestService constraint surfacing + CLUB identity ingest dedupe/keying)*
  <br>DONE: the diagnosed root cause was NOT the CLUB identity-pgcopy collision the line guessed — the
  backend.log dig (operator-supplied diagnosis) showed `sqlstate=23505` on **`ux_pda_composite`** = UNIQUE
  `(planning_day_id, assigned_person_id, assignment_type_id)` (V4:339) on `t_planning_day_assignment`. **Mechanism:**
  the J-6 T-16 23503 fix REMAPS each assignment's `planning_day_id` onto the kept-first surviving day; the real
  FLSTest fixture has TWO dup days ('Test'/'Test2') on the same `(Club, Day, LSZK)`, BOTH carrying an assignment —
  and when both share the SAME `(person, type)` the remap collapses them onto the ONE survivor → two identical
  composite rows → 23505. The J-6 remap fixed the FK but introduced this composite collision. **Fix:** (1)
  `PLANNING_DAY_ASSIGNMENT` producer SELECT (`MapperLegacyBindings`) now wraps the remapped projection in
  `ROW_NUMBER() OVER (PARTITION BY KeptPlanningDayId, AssignedPersonId, AssignmentTypeId ORDER BY <live-first
  (DeletedOn IS NULL), then earliest CreatedOn, then GUID>) … WHERE composite_rn = 1` — keep-firsts one row per
  POST-REMAP composite, live wins over soft-deleted (heeds the gap-hunter "producer dedupe is soft-delete-blind"
  rider; `ux_pda_composite` is a partial unique `WHERE deleted_on IS NULL` so only live rows collide). (2) Dropped
  dups recorded as `PLANNING_DAY_ASSIGNMENT_DUPLICATE` (`ProducerDropReconciliation`, in `ROW_DROP_CODES`),
  mirroring `PLANNING_DAY_DUPLICATE`. (3) Un-masked the constraint name: `MigrationBundleIngestService` injects
  `Environment` + under dev/test profiles reads `PSQLException.getServerErrorMessage().getConstraint()` into the
  error detail (`[sqlstate=23505, constraint=ux_pda_composite]`); prod stays masked. (4) Extended
  `PlanningDayProducerDedupeIT` with the synth case (two dup days, shared `(person, type)`, one live + one
  soft-deleted) → exactly ONE survivor (the live earliest), distinct-composite untouched. **Secondary
  "reservation did not migrate":** SAME root cause — ingest is a SINGLE Postgres transaction
  (`MigrationBundleIngestService` Javadoc:77), so the assignment 23505 aborts the WHOLE bundle txn → the
  reservation in the same per-club bundle never commits. Fixing the 23505 lets the bundle ingest fully →
  reservations land. **VERIFIED LOCALLY:** migration-bundle `check` green (ProducerDropReconciliationTest +
  MapperBindingContractTest + MapperLegacyBindingsTest); server `PlanningDayProducerDedupeIT` (3 ITs incl. the new
  composite case) green on Testcontainers; server arch-guards (ApplicationModules/ControllerAuditCoverage/
  NativeSqlRegister) + pmdMain/pmdTest/cpdRatchet green; migration-tool ExportCommandSmokeTest green; both
  modules compile clean. **DEFERRED-TO-FANOUT:** the producer SELECT runs against real MSSQL — synth bundles
  don't exercise it ([[project_synth_bundle_doesnt_validate_producer_select]]); the green fanout (sub-step d) +
  the reservation parity clearing is the manager-triggered fanout's proof.
- [x] **T-18 — real-idp seed↔spec reconciliation (gate-revealed, batch).** The real-idp proof surfaced
  sequential seed gaps: location-summary (fixed T-16), then `person report` expects a `Pilot (Towing)` summary
  row but the seed never makes the reported person a tow pilot (row count 0). To stop the ~20-min-per-cycle
  ping-pong, audit EVERY assertion in `flight-reports-parity.spec.ts` against `reporting-parity-fixture.ts` in
  ONE pass and make the seed satisfy all of them: person is PilotOrStudent on glider + motor + tow flights (→
  Pilot Glider/Motor/Towing rows, non-zero TotalFlights — the legacy-bug correction); instructor solo + non-solo
  split; nested aerotow row; location grouping ≥2 types; tenant-isolation club-B flight; Excel export. *(seam:
  reporting-parity-fixture seed completeness vs the spec's full assertion set)*