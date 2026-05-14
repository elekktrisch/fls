---
id: S-078
title: Delivery + DeliveryItem CRUD + Prepared → Booked transitions
epic: E-09
status: todo
depends_on: [S-014, S-077]
acceptance:
  - `Delivery` + `DeliveryItem` entities ported, `@TenantId`'d.
  - Delivery list/edit/delete endpoints.
  - **Delete-delivery resets affected flights' process state** (parity with legacy `DeleteDeliveriesAndUpdateProcessStatesOfFlight`). Audited.
  - `Prepared → Booked` transition supported via an endpoint; once Booked, no further mutations allowed (any PUT/DELETE returns 409).
  - SPA delivery list/edit screens at parity with legacy `flsweb/src/masterdata/deliveries/`.
estimate: L
adr_refs: [0005, 0008]
parity_test: tests/accounting/23-delivery-creation-workflow.spec.ts
---

## Context
Delivery is the output of the rules engine. The state machine is `Prepared → Booked` (terminal). The delete-resets-flights behavior is the kind of legacy quirk that must be preserved.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Entities + repository.
- [ ] Controller + DTOs.
- [ ] State transition logic with terminal-Booked guard.
- [ ] Delete-delivery resets flight states (call into the flight transition service from S-059).
- [ ] SPA store + screens.

## Notes
L because of the bidirectional integration with Flight state. Carefully integration-test the delete-resets-flights path — it's a high-impact destructive action.
