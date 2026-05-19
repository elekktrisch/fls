---
id: S-093
title: Inventory every Excel export (column/row spec)
epic: E-11
status: todo
depends_on: []
acceptance:
  - A reference doc `alpenflight/database/legacy-excel-exports.md` (or similar) lists every Excel-emitting code path in the legacy server with: source code location, sheet structure, column list with types/formats, sample input → sample output.
  - At least three exports inventoried: DeliveryMailExport, AircraftStatisticReport, FlightReports.
estimate: S
adr_refs: [0012]
parity_test: none
---

## Context
Without a column-spec inventory, we can't claim "feature-equivalence" (C16). This story is the spec for S-094..S-096.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Grep legacy code for EPPlus uses.
- [ ] For each, document the structure.
- [ ] Save a sample output file for each as a reference fixture.

## Notes
This can happen in parallel with E-01..E-05 — pure documentation.
