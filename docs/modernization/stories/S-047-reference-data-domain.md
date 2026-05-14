---
id: S-047
title: Reference-data domain (countries, unit types, member states, person categories, languages)
epic: E-06
status: todo
depends_on: [S-006, S-007, S-008, S-022]
acceptance:
  - All cross-tenant reference-data entities ported: Country, LengthUnitType, ElevationUnitType, CounterUnitType, StartType, MemberState, PersonCategory, Language.
  - GET endpoints exposed under `/api/v1/<entity>/listitems` (matching legacy URL shape for SPA caching consistency).
  - Seed data migrated from legacy `database/FLSTest/3 insert/` references.
  - Reference dropdowns in the SPA load these endpoints via the generated client and cache them in their respective Signal Stores.
estimate: M
adr_refs: [0005, 0008]
parity_test: tests/masterdata/03-masterdata.spec.ts (existing)
---

## Context
First domain port. Establishes the per-domain pattern that all later E-06+ stories follow.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Per entity: JPA `@Entity`, repository, controller, OpenAPI annotations.
- [ ] Seed data migration into Flyway migration (V2__reference_data_seed.sql) — *not* into V1__baseline (V1 is schema-only).
- [ ] SPA store per entity (or one combined `ReferenceDataStore` since these are small static lookups).
- [ ] Verify spec `03-masterdata.spec.ts` from legacy passes against new stack.

## Notes
These tables are not tenant-scoped. Their stores have long cache lifetimes (24h or more) — they almost never change.
