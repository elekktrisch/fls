---
id: S-009
title: Wire Flyway into Spring Boot + V1__baseline placeholder
epic: E-02
status: todo
depends_on: [S-001]
acceptance:
  - `org.flywaydb:flyway-core` + `flyway-database-postgresql` are in the dependency graph.
  - On Spring Boot startup against a fresh Postgres, Flyway runs the V1__baseline migration (placeholder one-table schema).
  - `flyway:info` and `flyway:validate` are wired into CI and fail the build on drift.
  - A `db/migration/` folder under `next/server/src/main/resources/` is the canonical location.
estimate: S
adr_refs: [0003]
parity_test: none
---

## Context
First DB story. Establishes the Flyway-driven migration model. V1__baseline is a placeholder; S-012..S-014 fill in the real schema.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Add Flyway dependencies and Spring Boot autoconfig properties.
- [ ] Create `db/migration/V1__baseline.sql` with one placeholder table (will be replaced by S-012).
- [ ] Configure `flyway.outOfOrder=false` (strict).
- [ ] Wire `flyway:info`/`validate` into CI.
- [ ] Add a Testcontainers-Postgres smoke test that boots the app and asserts the baseline migrated.

## Notes
Don't generalize too early: the V1__baseline grows incrementally in S-012..S-014. Once it's stable, **never amend** — every subsequent change is a new V*__ migration.
