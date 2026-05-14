---
id: S-077
title: Rules-engine port — glider→tow recursion via TowFlightId
epic: E-09
status: todo
depends_on: [S-076, S-063]
acceptance:
  - For a glider flight with `tow_flight_id` set, the rules engine recurses into the tow flight after processing the glider flight's items.
  - The recursion shares the same `Delivery` (one delivery covers both legs) or produces a separate delivery — confirm legacy behavior and match.
  - Unit tests cover: glider only; glider + tow (linked); orphan tow (no glider).
estimate: M
adr_refs: [0008]
parity_test: tests/accounting/32-rules-engine-per-type.spec.ts; deeper in S-107
---

## Context
The TowFlight recursion is parity-critical (R3). Easy to get wrong, hard to detect post-cutover.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Port the recursion logic.
- [ ] Confirm shared-Delivery vs separate-Delivery semantics from legacy.
- [ ] Tests.

## Notes
This is where the legacy code's elegance comes in — and where a subtle re-implementation can diverge. Line-by-line port + tests.
