# 0012 — Excel / report export library

- **Status:** Accepted
- **Date:** 2026-05-14
- **Decision criteria** (from [vision §6](../02-vision-and-constraints.md#6-decision-criteria-for-phase-3)): off-EOL · lower TCO · mature ecosystem · preserves sacred cows (delivery exports)

## Context

The current Excel export pipeline uses **EPPlus + Ionic.Zip** ([current-state §6 Server](../01-current-state.md#server)). EPPlus changed license from LGPL to **Polyform Noncommercial** at v4.5 ([R4](../01-current-state.md#r4--epplus-license-boundary)) — every later version is unusable for a commercial deployment without a paid license. The new stack is Java/Spring ([ADR 0001](0001-backend-language-and-framework.md)), so the .NET library question is moot anyway; this ADR picks the JVM equivalent.

[C16](../02-vision-and-constraints.md#3-hard-constraints) says exports must be **feature-equivalent**, not byte-exact — workbook structure can change as long as columns and values match. No known consumer parses the XLSX directly (Proffix consumes the API, not the file), so library choice is free of compatibility constraints.

The largest workload is the **DeliveryMailExportJob** ([server.md §"Job catalog"](../../legacy/server.md)) which bundles deliveries into per-recipient Excel files and zips them — must handle many sheets / large rows for a busy club.

## Options considered

### Option A — Apache POI (XSSF + SXSSF streaming)
- **Capabilities:** the canonical OSS Java library for Excel. XSSF is the in-memory XLSX implementation; SXSSF is the streaming variant that keeps only a sliding window of rows in memory — essential for large workbooks. Full feature surface: formulas, named ranges, styles, conditional formatting, charts, pivot tables. Apache 2.0 license, no ambiguity.
- **Fit to criteria:** off-EOL ✓ (active development). Lower TCO ✓ (free, no license fees). Mature ecosystem ✓ (the JVM Excel standard). Preserves sacred cows ✓ (covers every shape the current EPPlus output uses).
- **Migration cost:** medium — port each export shape from EPPlus's `ExcelPackage`/`ExcelWorksheet` API to POI's `Workbook`/`Sheet`/`Row`/`Cell`. APIs differ in idiom but map cleanly: cell types, styles, merged regions all available.
- **Ecosystem risk:** low.
- **Escape hatch:** XLSX itself is the lock-in surface; POI → FastExcel migration is straightforward for the simple cases.

### Option B — FastExcel / xlsx-streamer
- **Capabilities:** lightweight, streaming-focused, simpler API than POI.
- **Fit to criteria:** off-EOL ✓, TCO ✓, ecosystem ~ (smaller community than POI).
- **Why not chosen:** narrower feature surface — formula support, styling, conditional formatting are limited. If we discover an EPPlus feature in current exports that FastExcel doesn't support, we'd have to switch mid-rewrite. POI is the safer first cut.

### Option C — LibreOffice / OnlyOffice headless
- **Capabilities:** template-based document rendering via headless office automation.
- **Why not chosen:** introduces a heavyweight runtime dependency (a full office suite on the server) for what is straightforward tabular export. Over-engineering.

### Option D — Hand-crafted CSV only
- **Why not chosen:** feature regression from current XLSX exports. Users / Proffix-adjacent recipients expect XLSX.

## Decision

Chosen: **Option A — Apache POI**, using **SXSSF** for the delivery-mail-export job and any other large-workbook code path; XSSF for small reports where convenience matters more than memory ceiling. Apache 2.0 license is unambiguous; the API is well-documented and AI-assistable; feature ceiling exceeds anything the current EPPlus code does.

## Consequences

- **Positive:**
  - Free, OSS, no license-tier concerns ([R4](../01-current-state.md#r4--epplus-license-boundary) closed structurally).
  - Streaming variant handles large workbooks within bounded memory — important for clubs with many deliveries in a month.
  - Apache POI is the JVM Excel default; community answers and tutorials are abundant.
  - All current EPPlus features (cell types, styles, merged regions, formulas if used) have direct POI equivalents.

- **Negative:**
  - POI's API is verbose compared to FastExcel — expect more lines of code per export than the EPPlus equivalent. Mitigation: wrap commonly-used shapes (header row, styled rows, auto-sized columns) in a small helper class.
  - SXSSF's streaming-window model requires care: rows outside the window are flushed to disk and can't be re-accessed. Code must produce rows in order. Not a real constraint for our exports.
  - Ionic.Zip equivalent: use `java.util.zip.ZipOutputStream` (JDK-native) for the per-recipient-zip step in the delivery mail export — no third-party zip library needed.

- **Follow-ups (other ADRs / stories implied):**
  - **Story:** add `org.apache.poi:poi-ooxml` (and explicitly *not* `poi-ooxml-full` unless needed) to the backend Gradle/Maven config.
  - **Story:** inventory every Excel export in the current system (delivery export, monthly aircraft stats, flight reports CSV/XLSX) and document the column/row spec each emits. Becomes the test oracle for feature-equivalence ([C16](../02-vision-and-constraints.md#3-hard-constraints)).
  - **Story:** build a small `ExcelExportSupport` helper class — header styles, auto-size, date formatting, currency formatting — reused across all export sites.
  - **Story:** port the **DeliveryMailExportJob** to POI + SXSSF + `ZipOutputStream`; verify against a golden file produced from the legacy export.
  - **Story:** port the **AircraftStatisticReportJob** to POI; verify column-by-column against the legacy output (no e2e exists today per [R13](../01-current-state.md#r13--test-coverage-breadth-gaps-in-scheduled-jobs)).
  - **Story:** parity verification — for each export, generate from both systems against the same input data, compare values cell-by-cell with a tolerant comparator (ignores cosmetic differences like font name).
  - **Story:** delete `Alpinely.TownCrier` and `Ionic.Zip` from the modernization scope notes — replaced by `JavaMailSender` ([ADR 0013](.)) and `java.util.zip` respectively.
