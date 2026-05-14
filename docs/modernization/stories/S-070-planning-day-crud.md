---
id: S-070
title: PlanningDay CRUD + per-day reservation listing
epic: E-08
status: todo
depends_on: [S-068, S-051]
acceptance:
  - `PlanningDay` + `PlanningDayAssignment` entities ported, `@TenantId`'d.
  - Each PlanningDay has assigned flight instructor / tow pilot / flight operator (FKs to Person).
  - Edit screen shows the day's reservations inline (joins to `AircraftReservation`).
  - Spec `14-planning-day-crud.spec.ts` passes.
estimate: M
adr_refs: [0005, 0008]
parity_test: tests/planning/14-planning-day-crud.spec.ts
---

## Context
Per-day operations setup. Drives the planning notification email (S-086).

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Entities + repository.
- [ ] Controller + DTO (includes nested reservations for the day).
- [ ] SPA store + screens.

## Notes
Investigate `PlanningDaysRuleBased` from legacy — the name suggests rule-driven crew assignment, but actual behavior wasn't verified in the discovery doc. If it's just naming, drop it; if it's a real feature, port it.
