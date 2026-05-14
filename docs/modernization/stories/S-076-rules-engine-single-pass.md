---
id: S-076
title: Rules-engine port — single-pass rule types
epic: E-09
status: todo
depends_on: [S-075]
acceptance:
  - `InstructorFee`, `AdditionalFuelFee`, `LandingTax`, `StartTax`, `NoLandingTax`, `VsfFee` rules ported.
  - All are single-pass (no decrement loop — apply matching rules once each).
  - Unit tests per rule type.
estimate: M
adr_refs: [0008]
parity_test: tests/accounting/32-rules-engine-per-type.spec.ts; deeper in S-107
---

## Context
The remaining rule types. Less subtle than S-074/S-075 because they don't loop.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Per-rule-type port.
- [ ] Tests.

## Notes
NoLandingTax is the "opt-out" pattern — when matched, it suppresses any LandingTax rule. Confirm semantics against legacy.
