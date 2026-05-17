---
id: S-086
title: Port PlanningDayNotificationJob + email template
epic: E-10
status: todo
depends_on: [S-082, S-070]
acceptance:
  - Job emails tomorrow's planning-day status + 7-day-ahead reminders to assigned instructors / pilots.
  - Two templates: imminent (tomorrow) + week-ahead.
  - Spec `08-email.spec.ts` passes for the planning-day portion.
estimate: M
adr_refs: [0009, 0013]
parity_test: tests/email/08-email.spec.ts
---

## Context
The planning-day comms loop. Required before a tenant goes `active` — instructors expect these reminders.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Job class.
- [ ] Two template ports.
- [ ] Tests.

## Notes
The 7-day-ahead window: confirm legacy timing. Could be different per club via EmailTemplate overrides (S-055).
