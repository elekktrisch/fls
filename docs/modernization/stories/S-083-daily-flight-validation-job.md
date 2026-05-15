---
id: S-083
title: Port DailyFlightValidationJob
epic: E-10
status: todo
depends_on: [S-081, S-059, S-061, S-038, S-062a]
acceptance:
  - The job runs nightly (cron in UTC; equivalent local time documented).
  - Validates `NotProcessed`/`Invalid` flights → `Valid` or `Invalid` per validation rules (S-062a ports the `FlightValidator`; this job reuses it).
  - Locks `Valid` flights ≥ 2 days old → `Locked` (via the time gate from S-061).
  - Iterates per-tenant (uses `runUnscoped` from S-023 to find candidates across all clubs, then sets tenant context per-club for the actual transitions).
  - Spec `22-flight-locking-workflow.spec.ts` passes when the job's `runOnce` is invoked.
  - Job emits the standard started/completed/failed events (S-038).
estimate: M
adr_refs: [0008, 0009]
parity_test: tests/flights/22-flight-locking-workflow.spec.ts
---

## Context
The first business job. Drives most of the flight lifecycle.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Job class with `@Scheduled(cron = ...)` + `@MeasuredJob`.
- [ ] Iterate clubs (unscoped); for each, scope-and-transition.
- [ ] Reuse `FlightValidator` from S-062a and time gates from S-061.
- [ ] Tests against test DB.

## Notes
This job is parity-sensitive: it produces the same transition outcomes as legacy. Bugs in this job's logic cascade — Locked flights drive Delivery creation.
