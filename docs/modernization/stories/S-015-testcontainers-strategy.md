---
id: S-015
title: Testcontainers test-DB strategy + helpers
epic: E-02
status: todo
depends_on: [S-009]
acceptance:
  - Test-DB strategy decided: **Testcontainers Postgres + transactional rollback per test** (recommended) or per-class clean migration. Document the decision.
  - A shared `@SpringBootTest`-with-test-DB base class is committed.
  - A `@WithTenant(clubId)` annotation or helper sets tenant context before each test (precondition for ADR 0008's tenant filter — see also S-022).
  - The hello-endpoint integration test from S-001 runs against the Testcontainers DB.
estimate: M
adr_refs: [0003, 0008]
parity_test: none
---

## Context
ADR 0003 deferred this to a phase-4 story. Choice has real implications for test runtime — transactional rollback is fast (~10ms per test); per-class clean migration is slower (~1s) but cleaner.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Add `org.testcontainers:postgresql` + `spring-boot-testcontainers`.
- [ ] Decide: transactional rollback (recommended for most tests) + per-class clean migration for tests that test transaction boundaries themselves.
- [ ] Build the base class(es).
- [ ] Add `@WithTenant` annotation backed by a JUnit extension.
- [ ] Add a "switch tenant" helper for tests that need to exercise cross-tenant behavior.
- [ ] Add a "no tenant" helper for tests that need to exercise the unscoped path (S-023).

## Notes
Testcontainers reuses a single Postgres container across the test JVM (`reuse=true`) — 30s startup amortized. Without reuse, every test class pays the cost.
