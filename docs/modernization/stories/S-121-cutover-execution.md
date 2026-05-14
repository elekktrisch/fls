---
id: S-121
title: Cutover-day execution
epic: E-14
status: todo
depends_on: [S-112, S-113, S-114, S-115, S-116, S-117, S-118, S-119, S-120, S-107, S-109]
acceptance:
  - The cutover runbook is executed end-to-end within the 6-hour window.
  - T3 smoke (S-110) passes against production within 30 minutes of cutover completion.
  - At least one real user logs in (after password reset), creates/edits a flight, succeeds.
  - OGN ingestion confirmed hitting new endpoint.
  - Proffix sync confirmed pulling from new endpoint.
  - Post-cutover monitoring dashboard shows no alerts during the first hour.
estimate: L
adr_refs: []
parity_test: none
---

## Context
The cutover. Everything else in this backlog is preparation for this single execution.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Execute the runbook step-by-step, on the calendar slot.
- [ ] Verify each gate before proceeding to the next.
- [ ] Communicate with users (status page, email).
- [ ] Smoke + spot-check.

## Notes
L because the wall-clock duration is hours. Tasks split is the runbook itself.
