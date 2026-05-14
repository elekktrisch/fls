---
id: S-018
title: ShedLock stub table in Flyway baseline
epic: E-02
status: todo
depends_on: [S-009]
acceptance:
  - The `shedlock` table is in V1__baseline (DDL per `net.javacrumbs.shedlock-provider-jdbc-template`).
  - The ShedLock dependency is added but **not** enabled — `@SchedulerLock` annotations are not yet applied to jobs.
  - A README under `next/server/src/main/resources/db/migration/` notes the migration path to multi-instance: flip a property + annotate jobs.
estimate: S
adr_refs: [0009]
parity_test: none
---

## Context
ADR 0009 chose Spring `@Scheduled` in-process. Single-instance for now; if K8s migration ever introduces multiple replicas, ShedLock is the escape hatch. Bake the table now to avoid a schema change later.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Add `net.javacrumbs.shedlock:shedlock-spring` and `shedlock-provider-jdbc-template` as `<scope>provided</scope>` or commented dependencies — present but not active.
- [ ] Add the standard ShedLock table to V1__baseline.
- [ ] Write a 5-line README explaining the activation path.

## Notes
This is intentionally a stub — don't activate ShedLock or annotate jobs. Activating it on a single-instance deploy is harmless but pointless.
