---
id: S-075
title: Rules-engine port — EngineTime decrement loop
epic: E-09
status: todo
depends_on: [S-074]
acceptance:
  - Same shape as S-074, but operating on `ActiveEngineTime` instead of `ActiveFlightTime`.
  - Unit tests mirror S-074's coverage.
  - Code-review against legacy line-by-line.
estimate: M
adr_refs: [0008]
parity_test: tests/accounting/32-rules-engine-per-type.spec.ts; deeper in S-107
---

## Context
Same mechanism as S-074, different time metric. Smaller story because the pattern is established.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Port the EngineTime loop.
- [ ] Tests.

## Notes
Easy to merge with S-074 in implementation if the pattern truly is identical — keep separate to make the audit trail clear.
