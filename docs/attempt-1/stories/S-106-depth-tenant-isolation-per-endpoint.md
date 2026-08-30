---
id: S-106
title: Expand Playwright depth — multi-tenant isolation per endpoint
epic: E-13
status: todo
depends_on: []
acceptance:
  - For every authenticated endpoint, a cross-tenant attempt: data exists in tenant A; user from tenant B attempts to read/write/delete it; assert 404 or 403 (per design).
  - Catalog all endpoints (not sampled like the legacy `25-multi-tenant-isolation.spec.ts`).
  - Green on legacy.
estimate: L
adr_refs: []
parity_test: self
---

## Context
R1 / R14 — closes the depth gap on tenant isolation.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] OpenAPI-driven endpoint catalog.
- [ ] Parameterized spec.
- [ ] Verify on legacy.

## Notes
L because of catalog size. Same parametrization technique as S-104 — once the harness is there, adding entries scales.

On the new system, this is the same shape as the CI test from S-024 — but via Playwright at the HTTP layer, not via JPA at the repository layer. Both layers should agree.
