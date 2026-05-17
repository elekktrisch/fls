---
id: S-079
title: DeliveryCreationTest harness + generateExampleDelivery endpoint
epic: E-09
status: todo
depends_on: [S-077, S-078]
acceptance:
  - `DeliveryCreationTest` + `DeliveryCreationTestItem` entities ported (regression-harness payload).
  - `POST /api/v1/deliverycreationtests/:id/run` — runs the rules engine against the stored flight + expected items; returns pass/fail with cell-level diff.
  - `GET /api/v1/deliverycreationtests/example/:flightId` — runs the engine *without persisting*, returns the would-be `DeliveryItem` set (dry-run).
  - SPA CRUD screens at parity with legacy `flsweb/src/masterdata/deliveryCreationTests/`.
  - Spec `20-delivery-creation-test.spec.ts` passes.
estimate: L
adr_refs: [0005, 0008]
parity_test: tests/accounting/20-delivery-creation-test.spec.ts
---

## Context
The regression harness that validates rules-engine parity. The corpus expansion (S-107) — which closes C11 — depends on this mechanism existing. **Verify whether legacy `DeliveryCreationTest` is invoked by any job or only on demand** (vision §8 open item) — confirm during implementation.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Entities + repository.
- [ ] Run-test endpoint (admin + club admin).
- [ ] Dry-run endpoint (preview without write).
- [ ] SPA list + edit screens.
- [ ] Diff-rendering UI (when run fails, show which DeliveryItems differed).

## Notes
L because the diff-rendering UX is real work. The diff engine + UI is the operator's daily tool when tuning rules; it must be excellent.
