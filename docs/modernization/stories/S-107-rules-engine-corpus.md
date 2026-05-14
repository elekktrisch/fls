---
id: S-107
title: Rules-engine combinatorial corpus (C11)
epic: E-13
status: todo
depends_on: [S-079]
acceptance:
  - For each production `AccountingRuleFilter` combination per club, at least one `DeliveryCreationTest` row exists with the expected `DeliveryItem` set.
  - The corpus runs zero-delta on the legacy system (oracle).
  - The corpus runs zero-delta on the new system after E-09 lands.
estimate: L
adr_refs: []
parity_test: self
---

## Context
C11 is the contractual cutover gate for the rules engine. Without this corpus, parity is unverifiable.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Extract the current rule-filter inventory per club from production DB.
- [ ] Per combination: identify a representative flight; capture the legacy DeliveryItem output as expected; commit as a DeliveryCreationTest.
- [ ] Run corpus on legacy to verify each case is reproducible (zero-delta).
- [ ] Run on new system; iterate on the engine until zero-delta there too.

## Notes
L. This is the long pole of E-13. Plan for substantial time — at least one full week of focused work, more if many clubs are in production.

The operator's time-budget answer to "rules-engine corpus expansion budget" (vision §8 open item) constrains this story's scope.
