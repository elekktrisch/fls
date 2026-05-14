---
id: S-071
title: Planning-setup wizard
epic: E-08
status: todo
depends_on: [S-070]
acceptance:
  - Multi-step wizard at `/planningsetup` creates planning days for a date range (e.g. all Saturdays in summer) with default assignments.
  - Spec `15-planning-setup-wizard.spec.ts` passes.
estimate: M
adr_refs: [0005]
parity_test: tests/planning/15-planning-setup-wizard.spec.ts
---

## Context
One-off-feeling feature but real — clubs use it to bulk-create planning days.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Backend endpoint for bulk PlanningDay creation.
- [ ] SPA wizard component (multi-step form pattern).
- [ ] Spec verification.

## Notes
Reuse the form pattern from S-007; the multi-step shape can be a state machine in the local store.
