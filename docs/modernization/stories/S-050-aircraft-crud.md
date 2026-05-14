---
id: S-050
title: Aircraft CRUD (+ aircraft types/states)
epic: E-06
status: todo
depends_on: [S-049]
acceptance:
  - `Aircraft`, `AircraftType`, `AircraftState`, `AircraftAircraftState`, `AircraftOperatingCounter` ported.
  - Aircraft is `@TenantId`'d (per-club).
  - The "Add aircraft" modal pattern works on the new SPA.
  - The aircraft → flight-type filter dropdowns (glider/tow/motor) work end-to-end.
  - Spec `26-aircraft-crud.spec.ts` passes.
estimate: M
adr_refs: [0005, 0008]
parity_test: tests/masterdata/26-aircraft-crud.spec.ts
---

## Context
Aircraft is referenced by Flight, Reservation, PlanningDay — most of the downstream feature graph depends on this story.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Entities + mappings.
- [ ] Controllers + DTOs.
- [ ] SPA stores + screens.
- [ ] Aircraft type discriminator wired correctly (drives flight-type filtering).
- [ ] Spec verification.

## Notes
`Aircraft.immatriculation` is also a filter key in the accounting rules engine (R3). Make sure it's queryable + indexed.
