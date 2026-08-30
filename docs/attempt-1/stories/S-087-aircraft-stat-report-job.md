---
id: S-087
title: Port AircraftStatisticReportJob (uses POI)
epic: E-10
status: todo
depends_on: [S-082, S-094]
acceptance:
  - Monthly job emails aircraft-usage report to club / owner as an Excel attachment.
  - Manual `year/month` override supported (per legacy admin endpoint).
  - Excel content is column-for-column parity with legacy output (verified by S-096 parity harness).
  - No e2e exists in legacy (R13); add a smoke test that asserts an email with attachment lands in Mailpit.
estimate: M
adr_refs: [0009, 0012, 0013]
parity_test: tests/email/aircraft-statistic-report-smoke.spec.ts (new)
---

## Context
First job that produces Excel — exercises POI + Thymeleaf + JavaMailSender together. No legacy spec (R13), so the new system inherits a gap unless we add a smoke test.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Port the report query.
- [ ] Produce Excel via `ExcelExportSupport` (from S-094).
- [ ] Attach to email; send via JavaMailSender.
- [ ] Smoke test.
- [ ] Run S-096 parity harness against a fixture.

## Notes
Adding the smoke test that's missing on the legacy side is intentional — closing R13.
