# Excel parity fixtures (story S-096)

The cell-by-cell XLSX parity harness for AlpenFlight's synchronous Excel exports.
The comparator (`ch.alpenflight.platform.excel.ExcelParityComparator`, test scope)
reads two `.xlsx` workbooks and is **strict on values, types, and number-format
strings** but **tolerant of cosmetic differences** (font, exact column width,
header bold/fill). It runs in `check` (via the standard `test` task) and fails the
build on any column-or-value mismatch.

## Scope — FlightReports only at J-7

| Export | Fixture | Harness test | Status |
| --- | --- | --- | --- |
| **FlightReports** | `flight-reports-legacy-golden.xlsx` | `FlightReportExcelParityIT` + `FlightReportGoldenFixtureTest` | **covered (J-7)** |
| DeliveryMailExport | — | — | **deferred → J-10** (reuses this comparator, no rebuild) |
| AircraftStatisticReport | — | — | **deferred → J-10** (reuses this comparator, no rebuild) |

S-096's acceptance lists all three exports; only **FlightReports** is wired here.
`DeliveryMailExport` and `AircraftStatisticReport` ride **J-10** (which builds those
exports) — they add fixtures + a test each against the *same* comparator. This is
not "all three covered". Do not read it as such.

## Fixture provenance — golden, NOT live-legacy

`flight-reports-legacy-golden.xlsx` is **derived from the S-093 export inventory +
the legacy behavior oracle** (the J-7 "Parity decisions" note +
`FlightReportService.cs:743-859`), **not** from a live legacy `.xlsx` export.

The legacy `flsserver` stack (Mono + MSSQL + EPPlus) **does not run on the
Alpine/musl dev box** — there is no way to produce a live legacy export here. This
mirrors how T-01 handled the deferred legacy screenshots
(`alpenflight/web/e2e/legacy-reference/reporting/PENDING.md`): we encode the
*documented* contract now and defer the live byte-match to the fan-out gate.

**What this harness proves:** our `FlightReportExcelWriter` reproduces the
documented legacy layout cell-for-cell (values + number-formats).

**What it does NOT prove:** a byte-match against a *real* legacy export. That is a
**fan-out-gate concern** — when the fan-out workflow brings up the legacy stack, a
live-legacy `.xlsx` swaps in here (same lineage as the deferred legacy shots) and
the harness re-runs unchanged.

## Regenerating the golden fixture

The fixture is a deterministic snapshot of `FlightReportGoldenFixture` (the
contract-in-code, built by hand independent of the production writer).
`FlightReportGoldenFixtureTest` guards the committed bytes against drift.

To regenerate after a legitimate contract change (or a live-legacy swap-in):

```sh
./gradlew :alpenflight-server:testClasses
java -cp "$(./gradlew -q :alpenflight-server:printTestRuntimeClasspath)" \
  ch.alpenflight.flights.web.FlightReportGoldenFixtureGenerator \
  alpenflight/server/src/test/resources/excel-parity/flight-reports-legacy-golden.xlsx
```

Then re-run `./gradlew :alpenflight-server:check` and commit the new bytes.
