---
id: S-095
title: Port flight-reports synchronous Excel export
epic: E-11
status: todo
depends_on: [S-065, S-094]
acceptance:
  - `GET /api/v1/flightreports/.../export?format=xlsx` returns an Excel attachment.
  - Excel content is column-for-column parity with legacy (verified by S-096).
  - Streaming download (no buffering whole file in memory).
estimate: M
adr_refs: [0012]
parity_test: tests/reporting/16-flight-reports-generation.spec.ts (extends)
---

## Context
The synchronous export side of flight reports — the scheduled report jobs (S-087, S-090) use S-094 differently. This is the user-clicks-export-button case.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Backend endpoint that streams XLSX.
- [ ] SPA "export" button on flight-reports screens.
- [ ] Parity verification against legacy fixture.

## Notes
Same POI helper from S-094 is used.
