---
id: J-7
title: Flight reports (/flightreports — canned + custom builder + Excel export)
epic: E-07/E-11
status: todo
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
  - "[happy] Excel parity: the exported FlightReports .xlsx is cell-for-cell equal to the legacy fixture (parity harness green; cosmetic font/width diffs tolerated, values/types not)."
  - "[key-error] Tenant isolation: club-admin from club A filtering by a club-B location sees no club-B flights (empty/scoped, not a leak)."
  - "[edge] A filter matching no flights renders the empty-state copy (no crash, summary + table both empty)."
  - "[edge] Person-report summary groups by crew function (Pilot / Copilot / Instructor / InstructorSoloFlights + Total); tow-flight columns nest under each glider row where a tow exists."
screen: /flightreports   # replacing legacy flsweb/src/reporting/ (FlightReportsModule.js)
headless_pulled_in: "Excel synchronous export infra (S-093/094/095/096) → homed here as the first sync-export consumer: POI ExcelExportSupport helper + the cell-diff parity harness. Scope at J-7 = build helper + harness, cover FlightReports export only. DeliveryMailExport + AircraftStatisticReport parity coverage RIDE J-10 (harness reused, not rebuilt)."
migration: "N/A — read-side. Reuses J-2's migrated Flight + FlightCrew data; no new mapper."
parity_test: alpenflight/web/e2e/tests/reporting/flight-reports.spec.ts, alpenflight/web/e2e/tests/reporting/custom-builder.spec.ts, alpenflight/web/e2e/tests/real-idp/flight-reports-parity.spec.ts
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
