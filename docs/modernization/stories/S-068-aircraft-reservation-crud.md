---
id: S-068
title: AircraftReservation CRUD + validation
epic: E-08
status: todo
depends_on: [S-050, S-051]
acceptance:
  - `AircraftReservation` + `AircraftReservationType` entities ported, `@TenantId`'d.
  - Create/edit/delete + paginated list (`POST /api/v1/aircraftreservations/page` shape preserved for SPA compatibility).
  - Conflict detection: a reservation cannot overlap another reservation on the same aircraft + time range — rejected with 409 Conflict.
  - Spec `10-reservations-crud.spec.ts` passes.
estimate: M
adr_refs: [0005, 0008]
parity_test: tests/reservations/10-reservations-crud.spec.ts
---

## Context
Reservation conflict rules are real domain logic; conflict rejection is one of the unprobed depth gaps in legacy (R14).

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Entities + repository.
- [ ] Controller + paginated list endpoint.
- [ ] Conflict-detection service (overlap query with exclusion on the current reservation ID for updates).
- [ ] SPA store + screens.

## Notes
Conflict depth tests added in S-101 / S-105.
