---
id: S-065
title: Flight reports + custom report builder
epic: E-07
status: todo
depends_on: [S-062a, S-093]
acceptance:
  - Server-side `FlightReportService` ported.
  - SPA reports: pre-canned reports (`/flightreports/:category/:type`); custom report builder (`/flightreports/custom/:category/:filter/...`).
  - Excel export uses POI (`ExcelExportSupport` from S-094).
  - Specs `16-flight-reports-generation.spec.ts` and `17-custom-report-builder.spec.ts` pass.
estimate: L
adr_refs: [0005, 0012]
parity_test: tests/reporting/16-flight-reports-generation.spec.ts, tests/reporting/17-custom-report-builder.spec.ts
---

## Context
Reports surface is wide (pre-canned categories + custom builder). HighCharts visualization on top.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Server: ReportService + endpoints per category.
- [ ] Custom report DTO: filter spec + grouping spec + columns.
- [ ] SPA: report picker, custom builder UI, chart rendering.
- [ ] Export buttons (Excel via S-094).

## Notes
L because the custom builder has a lot of UI state. Consider whether to port HighCharts specifically or switch to a modern alternative (Chart.js, ng2-charts). Recommend matching legacy library family (HighCharts has a modern Angular wrapper) to keep visual parity.
