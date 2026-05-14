---
id: S-073
title: Rules-engine port — IgnoreFlight + Recipient stages
epic: E-09
status: todo
depends_on: [S-072, S-058]
acceptance:
  - `IgnoreFlightRulesEngine` ported: `DoNotInvoiceFlight` rules iterated; if any match, the flight is skipped (no Delivery produced).
  - `RecipientRulesEngine` ported: `Recipient` rules iterated; matched rules set the `Delivery.recipient_person_id`.
  - **Documented**: the rule-evaluation order when multiple Recipient rules match (vision §8 open item) — confirmed from legacy: first-match-wins.
  - Unit tests: one positive case for each engine; one negative (no matches → fall-through behavior); ambiguity case (multiple matches → first wins).
estimate: M
adr_refs: [0008]
parity_test: tests/accounting/32-rules-engine-per-type.spec.ts (depth in S-107)
---

## Context
First two stages of the rules pipeline (SERVER.md §3). Smallest sub-port; gets the test scaffolding in place.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Build the `RuleBasedDeliveryDetails` accumulator class.
- [ ] Port `IgnoreFlightRulesEngine`.
- [ ] Port `RecipientRulesEngine`.
- [ ] Document evaluation order in `next/server/docs/rules-engine.md`.
- [ ] Tests.

## Notes
Resolves vision §8 open item on Recipient ordering. Don't skip — first-vs-last-match-wins is the kind of subtle behavior whose drift would silently mis-route invoices.
