---
id: S-049
title: Locations CRUD
epic: E-06
status: todo
depends_on: [S-047, S-022]
acceptance:
  - `Location` + `LocationType` + `InOutboundPoint` ported.
  - Location is `@TenantId`'d (per-club).
  - List/edit screens use the kit components from S-008.
  - Spec `12-masterdata-crud.spec.ts` parity for locations passes.
estimate: M
adr_refs: [0005, 0008]
parity_test: tests/masterdata/12-masterdata-crud.spec.ts
---

## Context
Locations are per-club master data (a club's flight points). Good early E-06 port — small surface, real tenant scoping.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] JPA entities + mappings.
- [ ] Controllers + DTOs.
- [ ] SPA store + edit/list screens.
- [ ] Spec verification.

## Notes
Some locations may be cross-club (commonly-used airports) — confirm with the tenant-scope catalog (S-011) whether Location is fully tenant-scoped or whether there's a shared catalog with per-club references. If shared, this story needs adjustment.
