---
id: S-085
title: Port LicenceNotificationJob + email template
epic: E-10
status: todo
depends_on: [S-082, S-051]
acceptance:
  - Job emails licence-expiry warnings for medical certs + instructor licences (60-day window — confirm legacy threshold).
  - Thymeleaf template for the warning email.
  - Spec `08-email.spec.ts` passes for the licence-notification portion.
estimate: M
adr_refs: [0009, 0013]
parity_test: tests/email/08-email.spec.ts
---

## Context
Person/licence data lives on Person (cross-tenant) but the email is sent per-club (because PersonClub determines who gets the reminder). Tenancy is interesting here.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Job class.
- [ ] Template port.
- [ ] Tenancy: iterate clubs; for each, find PersonClub rows where the linked Person has an expiring credential.

## Notes
Confirm the 60-day window matches legacy. Other parameters: which licences are watched, what types of credentials trigger.
