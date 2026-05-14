---
id: S-053
title: Flight types + flight cost balance types CRUD
epic: E-06
status: todo
depends_on: [S-050]
acceptance:
  - `FlightType` and `FlightCostBalanceType` ported with `is_for_glider`/`is_for_tow`/`is_for_motor` flags.
  - List/edit screens; flag-based filtering matches legacy UI.
  - Spec `29-flight-type-crud.spec.ts` passes.
estimate: S
adr_refs: [0005, 0008]
parity_test: tests/masterdata/29-flight-type-crud.spec.ts
---

## Context
Both are referenced by Flight and by AccountingRuleFilter — pre-req for E-07 + E-09.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Entities + mappings + controllers + DTOs.
- [ ] SPA stores + screens.
- [ ] Spec verification.

## Notes
Small surface; nothing tricky.
