---
id: S-110
title: T3-equivalent smoke against new stack
epic: E-13
status: todo
depends_on: [S-020, S-021, S-062]
acceptance:
  - A smoke spec: log in (via OIDC), GET `/api/v1/users/my`, GET a flight, PUT an update, GET again to confirm persistence.
  - Runs in CI on every PR.
  - Runs in production post-deploy as a synthetic health check.
estimate: S
adr_refs: []
parity_test: self
---

## Context
The "T3 sequence" from current-state §6 — minimum bar for "the system is alive."

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Write the smoke spec.
- [ ] Wire to CI.
- [ ] Wire to post-deploy synthetic monitoring.

## Notes
This is the spec the uptime probe (S-037) doesn't catch — it tests the full auth + DB + EF round-trip, not just `/actuator/health`.
