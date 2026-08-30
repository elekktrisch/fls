---
id: S-080
title: Proffix-compatible API surface verification
epic: E-09
status: todo
depends_on: [S-078, S-029, S-115]
acceptance:
  - The new `/api/v1/deliveries/*` GET endpoints return payloads schema-identical to the legacy server (verified by recording a sample legacy response and diff'ing the new one).
  - A smoke test simulates `PROFFIX-FLS-Sync`'s call pattern (authenticate via client-credentials, GET deliveries list, GET single delivery, mark as booked via PUT).
  - Differences (if any) are documented and either fixed or flagged to the Proffix maintainer.
estimate: M
adr_refs: [0005]
parity_test: tests/proffix/proffix-contract.spec.ts
---

## Context
Sacred cow — Proffix integration must keep working. Vision §8 open item (where the live Proffix code lives) must be resolved by this story.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Capture a few sample legacy responses from the production `/api/v1/deliveries/*` endpoints.
- [ ] Diff against the new server's output.
- [ ] Fix any differences in the new server (don't ask the Proffix side to adapt).
- [ ] Smoke test the full Proffix call pattern.
- [ ] Coordinate with the Proffix maintainer for a parallel test (S-115).

## Notes
Depends on S-115 because the maintainer's cooperation is needed to do a real parallel test. Schema verification can proceed without them; behavior verification needs them.
