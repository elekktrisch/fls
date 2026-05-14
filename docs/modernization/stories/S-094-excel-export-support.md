---
id: S-094
title: ExcelExportSupport helper class
epic: E-11
status: todo
depends_on: [S-001, S-093]
acceptance:
  - `ch.fls.excel.ExcelExportSupport` provides: `headerRow(...)`, `dataRow(...)`, `currencyCell(...)`, `dateCell(...)`, `autoSize(...)`, `streamingWorkbook()`.
  - Default styles match legacy output (header bold + bottom-border; currency right-aligned with locale-specific separators; dates as ISO).
  - Backed by SXSSF for streaming-mode workbooks.
  - One unit test per helper.
estimate: M
adr_refs: [0012]
parity_test: none
---

## Context
The wrapper that keeps POI verbosity out of feature code. Every export site uses this.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Add POI dependencies (`poi-ooxml`, plus `poi-ooxml-full` only if needed for richer features).
- [ ] Build the helper class.
- [ ] Unit tests.

## Notes
SXSSF window size: 100 rows is the POI default and works for our scale.
