---
id: S-074
title: Rules-engine port — FlightTime decrement loop
epic: E-09
status: todo
depends_on: [S-073]
acceptance:
  - `FlightTime` rules applied iteratively: each matching rule emits a `DeliveryItem` and decrements `ActiveFlightTime` on the accumulator.
  - Loop terminates when no rule matches the remaining `ActiveFlightTime` — exactly the legacy semantics.
  - Unit tests: tiered billing case (e.g. first 30 min @ rate A, next 30 @ rate B, remainder @ rate C → 3 DeliveryItems).
  - Edge case: zero flight time → no DeliveryItems.
  - Edge case: flight time exceeds all configured tiers → final DeliveryItem covers the tail (or as legacy does).
estimate: L
adr_refs: [0008]
parity_test: tests/accounting/32-rules-engine-per-type.spec.ts; deeper in S-107
---

## Context
The decrement-loop is the sacred-cow mechanism (R3). This is the highest single-story risk in the rewrite. Treat with extreme care.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Translate the loop from legacy `DeliveryItemRulesEngine.cs` line-by-line, not "rewrite from understanding."
- [ ] Wire to the accumulator.
- [ ] Tests covering tiering, zero, overflow.
- [ ] Code-review the port against the legacy source side-by-side.

## Notes
L because the parity bar is bit-exact. Reading the legacy code and verifying each line is preserved is the bulk of the work, not the typing.

If during the port you spot a legacy bug, **do not silently fix it**. Document it; raise with the operator. Customer invoices depend on the current behavior, bugs and all.
