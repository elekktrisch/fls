---
id: S-072
title: AccountingRuleFilter + filter-type CRUD
epic: E-09
status: todo
depends_on: [S-014, S-053, S-054]
acceptance:
  - `AccountingRuleFilter` + `AccountingRuleFilterType` + `AccountingUnitType` + `FlightCrewType` entities ported, `@TenantId`'d on the filter (filter types and unit types are reference data).
  - The filter UI mirrors legacy `flsweb/src/masterdata/accountingRules/` — list view + edit screen with all the predicate fields (aircraft type, immat list, locations, flight type codes, crew types, member numbers, homebase, time ranges).
  - Spec `21-accounting-rules-edit.spec.ts` passes.
  - Audit-log entries on every mutation — critical since rule changes affect every subsequent invoice.
estimate: L
adr_refs: [0005, 0008]
parity_test: tests/accounting/21-accounting-rules-edit.spec.ts
---

## Context
The configuration surface for the sacred cow. Get the form fields right; the rules engine instantiates `Rule` objects from these rows at runtime.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Entities + repository (with jsonb `filter_config` column per S-014).
- [ ] Controller + DTOs.
- [ ] Reference-data endpoints for filter types + unit types + crew types.
- [ ] SPA store + edit screen with conditional fields per filter type.
- [ ] Audit-log integration with extra emphasis (this is the highest-impact mutation category).
- [ ] Spec verification.

## Notes
L because the edit form has conditional sections per filter type and the legacy form is intricate. Reference `Invoice-Rule-Editor-Form-Design.vsdx` and `InvoiceRuleFilters.xlsx` from `flsserver/doc/` for the design intent.
