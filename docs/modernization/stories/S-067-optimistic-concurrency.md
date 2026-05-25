---
id: S-067
title: Optimistic-concurrency strategy on Flight (ETag / version column)
epic: E-07
status: todo
depends_on: [S-058, S-062c]
acceptance:
  - `flight.version` `@Version` column added (or `etag` derived).
  - PUT endpoints accept `If-Match: <version>` header; 412 Precondition Failed on mismatch.
  - SPA forms include the version in mutations.
  - A test simulates two clients editing the same flight; second commit gets 412.
  - **Aerotow happy-case e2e spec** (`04c-flights-paired-create.spec.ts` — deferred from S-062c's AC10): walks the wizard with start type = Aerotow, fills every editable attribute (flightDate, startTypeId, glider + tow aircraft, pilot/coPilot/instructor/observer/passenger/winchOperator slots as applicable, start/landing locations, outbound/inbound routes, start/landing times, nrOfLdgs, engine counters, flightCostBalanceType, invoiceRecipient, couponNumber, comment, isSoloFlight), submits, and asserts the POST→POST→PUT-link orchestration fired in order with the `If-Match` header on the link PUT. Then reloads via `GET /flights/{id}` + the linked tow GET and asserts every attribute round-tripped byte-identical. Compensating-DELETE-on-tow-fail variant covered in the same file.
estimate: M
adr_refs: [0005]
parity_test: alpenflight/web/e2e/tests/flights/04c-flights-paired-create.spec.ts
---

## Context

R14 callout: concurrent-edit behavior is untested in legacy. New system should handle it properly from the start — Flight is the highest-frequency editable entity.

S-062c shipped the `If-Match` plumbing on every PUT (`FlightStore.updatePair`, `FlightStore.savePair` link PUT, `FlightStore.deleteOne`) but the server `@Version` column doesn't exist yet, so the header is currently advisory. This story makes the column real and turns the existing plumbing into an end-to-end concurrency gate.

## Coverage gap S-062c left on the table

S-062c shipped two ported parity specs (`04-flights-create`, `05-flights-edit`) and only the self-start happy path. The wizard's **aerotow** happy path — the case that actually exercises the paired-create POST→POST→PUT-link orchestration + the If-Match header on the link PUT + every editable attribute round-tripping — has **no e2e coverage today**. The only AEROTOW-aware tests are `flight-form.model.spec.ts` unit assertions on the DTO mapper's output shape; they don't verify the server actually persisted the fields nor that the read-back round-trips them.

That coverage is in scope for this story because:

1. `@Version` lands here, making the If-Match header on the link PUT a real concurrency gate rather than advisory plumbing.
2. The full-attribute round-trip on aerotow is the natural smoke test for the paired-create + If-Match combination together.
3. Without it, a regression in any of the 25+ fields the mapper handles would only surface in production.

The spec file already has a reserved name (`04c-flights-paired-create.spec.ts`) and the orchestration code shipped at `alpenflight/web/src/app/features/flights/flight.store.ts:savePair` — the refine should derive the test plan against that code.

## Tasks

- [ ] Add `@Version` to Flight; Flyway migration for the column (structural per ADR 0022 directive 2 — no domain-rule additions).
- [ ] Controller PUT methods accept and honor `If-Match`; on mismatch return 412 with a structured body.
- [ ] OpenAPI documents the header + 412 response so the SPA generates typed access to the headers / error shape.
- [ ] SPA forms pass the version through (FlightStore detail slice already plumbs `currentVersion` / `currentTowVersion`; the link PUT already sends `If-Match: <gliderVersion>`).
- [ ] **`04c-flights-paired-create.spec.ts`** — aerotow happy-case full-attribute round-trip + paired-create order assertion + compensating-DELETE-on-tow-fail variant (see frontmatter AC).
- [ ] Concurrency-race spec: two clients PUT the same flight; first wins, second gets 412.

## Notes

Apply this to other high-edit entities in their respective stories (Aircraft, Reservation, PlanningDay) if the operator wants — for now scoped to Flight.
