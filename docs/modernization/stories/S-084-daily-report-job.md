---
id: S-084
title: Port DailyReportJob + email template
epic: E-10
status: todo
depends_on: [S-082, S-083]
acceptance:
  - Job emails per-pilot / per-instructor daily flight reports.
  - Thymeleaf template ported from legacy `Alpinely.TownCrier` source under `flsserver/src/FLS.Server.Service/Email/`.
  - `runOnce` against the e2e fixture produces emails captured by Mailpit.
  - Spec `08-email.spec.ts` passes for the daily-report portion.
estimate: M
adr_refs: [0009, 0013]
parity_test: tests/email/08-email.spec.ts
---

## Context
The first email-emitting job. Establishes the template-port pattern for later jobs.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Job class.
- [ ] Port the Thymeleaf template (port content; rewrite syntax).
- [ ] Test against Mailpit.

## Notes
Use the test infrastructure from S-082's worked example.
