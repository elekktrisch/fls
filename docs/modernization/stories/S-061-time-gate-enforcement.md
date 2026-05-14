---
id: S-061
title: Time-gate enforcement (≥2d lock, ≥3d bill)
epic: E-07
status: todo
depends_on: [S-059]
acceptance:
  - `LockFlights()` (the bulk transition Valid → Locked) requires `flight.flight_date <= today - 2 days`.
  - `CreateDeliveriesFromFlights()` (Locked → DeliveryPrepared) requires `flight.locked_at <= today - 3 days`.
  - Both gates expressed as policy beans so tests can override them (e.g. set "today" to a fixed date for the e2e fixture).
  - Unit tests verify boundary behavior: 1 second before the gate (rejected), 1 second after (allowed) — exactly 2/3 days.
estimate: M
adr_refs: [0008]
parity_test: tests/flights/22-flight-locking-workflow.spec.ts (legacy); boundary depth added in S-103
---

## Context
Sacred-cow time-gate. The e2e fixture anchors to 2026-01-01 so legacy tests can hit gates; new system should preserve the same fixture-friendliness.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] `FlightGatePolicy` bean exposing `canLock(flight, now)` and `canBill(flight, now)`.
- [ ] Inject `Clock` so tests can manipulate "now" without changing system clock.
- [ ] Wire into the transition service from S-059.
- [ ] Boundary tests.

## Notes
Use Java's `Clock` (`java.time.Clock`) injected as a Spring bean. Test code overrides with `Clock.fixed(...)`. This is the way to test time without `Thread.sleep` or freezing the OS clock.
