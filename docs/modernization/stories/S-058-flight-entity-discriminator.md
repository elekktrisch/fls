---
id: S-058
title: Flight entity + FlightAircraftType discriminator
epic: E-07
status: todo
depends_on: [S-013, S-050, S-051, S-053]
acceptance:
  - `Flight` JPA entity covers all columns from the legacy `Flight` table (with reshape per S-013).
  - `FlightAircraftType` enum: GliderFlight, TowFlight, MotorFlight — modeled as a JPA `@Enumerated` smallint discriminator.
  - The single-entity model is preserved (no separate GliderFlight/TowFlight/MotorFlight classes — discrimination in code, not in schema).
  - Repository finder methods: `findByOperatingClub(...)` (auto-filtered by `@TenantId`), `findByTowFlightId(...)`, `findByProcessState(...)`.
  - Basic CRUD endpoints (no state-machine logic yet — that's S-059).
estimate: M
adr_refs: [0008]
parity_test: none
---

## Context
Sacred-cow shape — single Flight entity for glider/tow/motor, discriminated by FlightAircraftType. Don't split into multiple tables.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] JPA entity.
- [ ] Repository.
- [ ] Basic controller + DTOs (`FlightListItem`, `FlightDetail`, `FlightCreate`).
- [ ] Smoke test creating each of the three types.

## Notes
Crew references live in `FlightCrew` (M:N to Person). Add basic FlightCrew handling here even though it's referenced by E-07's later stories.
