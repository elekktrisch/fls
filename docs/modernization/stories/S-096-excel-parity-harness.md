---
id: S-096
title: Excel parity-verification harness
epic: E-11
status: todo
depends_on: [S-094, S-095]
acceptance:
  - A test harness reads two XLSX files and produces a cell-by-cell diff, tolerant of cosmetic differences (font name, exact column width).
  - Configured with the legacy output fixtures from S-093 + a current-system output.
  - Runs in CI; fails the build on column-or-value mismatches.
  - Covers: DeliveryMailExport, AircraftStatisticReport, FlightReports.
estimate: M
adr_refs: [0012]
parity_test: none
---

## Context
The "feature-equivalent" (C16) check. Without this, "exports match" is a vibe.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Build the diff tool.
- [ ] Configure ignore rules (font, column widths).
- [ ] Hook into CI.
- [ ] Wire fixtures from S-093.

## Notes
Tolerance dimensions: cosmetic (style) is fine to ignore. Values (cell contents, types, formulas) are not.
