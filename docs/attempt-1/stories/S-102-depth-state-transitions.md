---
id: S-102
title: Expand Playwright depth — state-machine illegal transitions
epic: E-13
status: todo
depends_on: []
acceptance:
  - Specs covering illegal flight state transitions (each illegal pair from S-059's matrix) — e.g. `NotProcessed → Locked` directly, `DeliveryBooked → Valid`, `Invalid → Locked` without re-validation.
  - Specs covering the `Invalid → Valid` recovery path (re-validation after edits).
  - Specs covering re-validation idempotency (running validation twice with no changes).
  - All green on legacy first.
estimate: M
adr_refs: []
parity_test: self
---

## Context
R14 callout for state machine.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Specs per illegal transition.
- [ ] Specs for recovery paths.
- [ ] Verify on legacy.

## Notes
Combine with S-101 in execution if appetite — both expand the existing flight specs.
