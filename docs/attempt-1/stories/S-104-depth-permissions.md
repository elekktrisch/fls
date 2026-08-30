---
id: S-104
title: Expand Playwright depth — permission boundaries per endpoint
epic: E-13
status: todo
depends_on: []
acceptance:
  - For every authenticated endpoint, four-way permission tests: pilot, flight operator, club admin, unauthenticated.
  - Expected outcomes (allow/deny) documented per endpoint based on legacy behavior.
  - Tests green on legacy.
estimate: L
adr_refs: []
parity_test: self
---

## Context
R14: legacy permission boundaries are unprobed at the per-endpoint level.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Catalog endpoints (use the OpenAPI spec once available).
- [ ] Per endpoint × 4 roles → expected outcome.
- [ ] Build specs (parameterized over the catalog).

## Notes
L because of the catalog size. Once the test is parameterized, adding entries is cheap.
