---
id: S-089
title: Port DeliveryCreationJob (invokes rules engine)
epic: E-10
status: todo
depends_on: [S-081, S-077, S-078, S-061]
acceptance:
  - Job iterates Locked flights ≥ 3 days past lock (per S-061 time gate).
  - For each, invokes the rules engine (E-09 stack); produces Delivery + DeliveryItems; transitions flight to DeliveryPrepared or DeliveryPreparationError.
  - Failures per flight are logged + Sentry-captured but do not abort the job — other flights still get processed.
  - Spec `23-delivery-creation-workflow.spec.ts` passes when the job's `runOnce` is invoked.
estimate: M
adr_refs: [0008, 0009]
parity_test: tests/accounting/23-delivery-creation-workflow.spec.ts
---

## Context
The scheduled wrapper around E-09's rules engine. The engine itself is in E-09; this story is the cron-job wiring.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Job class.
- [ ] Iteration across clubs (unscoped find + scoped transitions).
- [ ] Per-flight error isolation.
- [ ] Tests.

## Notes
DeliveryPreparationError is the "no rules matched" outcome — flight is excluded from the auto-pipeline until the operator either updates rules or marks ExcludedFromDeliveryProcess. Match legacy.
