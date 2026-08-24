---
id: S-103
title: Expand Playwright depth — time-gate boundaries
epic: E-13
status: todo
depends_on: []
acceptance:
  - Specs probe the ≥2-day lock gate and ≥3-day delivery gate at the boundary: 1 second before (rejected) and 1 second after (allowed).
  - Boundary scenarios respect the e2e fixture's anchored-time semantics (`_test-fixture.sql` 2026-01-01 base).
  - Green on legacy.
estimate: M
adr_refs: []
parity_test: self
---

## Context
R14: gates are set up by fixtures but never probed at the boundary.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Build fixtures with flights anchored at gate-minus-1s and gate-plus-1s.
- [ ] Specs asserting the expected outcome.
- [ ] Verify on legacy.

## Notes
Time-boundary tests are flaky if the system clock drifts. Use the fixture's anchored time and assert against it directly.
