---
id: S-081
title: Spring @Scheduled infrastructure + idempotency + runOnce admin endpoint
epic: E-10
status: todo
depends_on: [S-001, S-026]
acceptance:
  - `@EnableScheduling` configured; a `TaskScheduler` thread-pool bean tuned for the workload (2–4 threads).
  - A `JobRegistry` (Spring component) holds all `@MeasuredJob`-annotated jobs and exposes them via `GET /api/v1/admin/jobs` (admin-only).
  - `POST /api/v1/admin/jobs/{name}/run` triggers a one-time execution of a named job (admin-only).
  - Convention documented: every job is idempotent — `runOnce` is safe to call multiple times.
  - Time-zone policy documented: cron expressions are UTC in code; equivalent local times noted in operator runbook.
estimate: M
adr_refs: [0009]
parity_test: none
---

## Context
The scheduling base every later S-083..S-090 builds on. Establishes the convention.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] `@EnableScheduling` + `TaskScheduler` bean.
- [ ] `JobRegistry` collecting `@MeasuredJob`s.
- [ ] Admin endpoints.
- [ ] Documentation of the per-job idempotency contract.

## Notes
The legacy "trigger a workflow via HTTP" pattern survives as the admin endpoint — but it's no longer the *scheduling* mechanism, just the manual-override mechanism.
