---
id: S-119
title: Force password-reset email queue (C14)
epic: E-14
status: todo
depends_on: [S-028, S-116]
acceptance:
  - At cutover-go-time, the script from S-028 fires the password-reset emails for all production users.
  - Email content (from Keycloak's reset-password template) is reviewed: it explains the rewrite + cutover + new login URL.
  - Drill: the email-send mechanism tested against staging Keycloak (≥10 emails) — they arrive, the reset links work end-to-end.
estimate: M
adr_refs: [0007]
parity_test: none
---

## Context
C14: passwords are not migrated; every user must reset. Cutover-day mass email.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Customize Keycloak's reset-password email template with FLS branding + cutover context.
- [ ] Drill on staging.
- [ ] Trigger at cutover.

## Notes
Sending many emails in a short window risks SMTP rate-limits — verify the chosen relay (S-091) can handle the volume. Also: consider batching with a 60s spread to avoid spam-flag risk.
