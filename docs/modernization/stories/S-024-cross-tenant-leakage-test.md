---
id: S-024
title: Cross-tenant leakage CI test (per-repository)
epic: E-03
status: todo
depends_on: [S-022, S-011]
acceptance:
  - A property-based test under CI exercises every repository: create data in tenant A; attempt to read it while tenant context is B; assert empty result (or 404 from a controller-level test).
  - The test is parameterized by the tenant-scoped entity list from S-011.
  - The test fails the build if added.
  - A separate dimension covers the "unscoped" path — explicitly running unscoped should return both tenants' data.
estimate: M
adr_refs: [0008]
parity_test: tests/multi-tenant/leakage-property-test.kt (or .java)
---

## Context
The CI-time enforcement that closes R1. ADR 0008 makes leakage structurally impossible from JPA, but: native SQL queries bypass the filter, and developers may add new repository methods that should respect tenancy. This test catches both.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Iterate the tenant-scoped entity list from S-011.
- [ ] For each, write a test that creates a row in tenant A, switches to tenant B, asserts the row is invisible.
- [ ] Add an assertion that controllers (the integration-test level) return empty list / 404 / 403 (per design) for cross-tenant attempts.
- [ ] Add an unscoped variant: same setup, but the read happens inside `runUnscoped(...)`; expect to see tenant A's row.
- [ ] Wire the test into CI to fail the build.

## Notes
Spec `25-multi-tenant-isolation.spec.ts` on the legacy side does this only on a sample. New system has zero excuse to skip any endpoint — every repository participates.
