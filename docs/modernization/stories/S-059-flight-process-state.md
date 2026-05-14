---
id: S-059
title: FlightProcessState stored state + transition matrix
epic: E-07
status: todo
depends_on: [S-058]
acceptance:
  - `FlightProcessState` enum: NotProcessed(0), Invalid(28), Valid(30), Locked(40), DeliveryPreparationError(45), DeliveryPrepared(50), DeliveryBooked(60), ExcludedFromDeliveryProcess(99).
  - A `FlightStateTransitions` table-driven implementation enumerates legal transitions (port of `FlightService.cs:1380-1440`).
  - Transition method `transition(flight, newState, actor)`:
     - Validates the transition is legal (raises `IllegalFlightTransitionException` otherwise).
     - Validates time-gates (S-061 — coupled but landed in S-061's tasks).
     - Writes audit event.
     - Persists.
  - **DeliveryBooked is terminal** — any transition out of it rejected.
  - Unit tests cover all defined transitions (positive) and at least 10 illegal transitions (negative).
estimate: L
adr_refs: [0008]
parity_test: tests/flights/06-flights-state-transitions.spec.ts (legacy spec, expanded in S-102)
---

## Context
The other half of the 2D state machine. Where most of E-07's parity risk lives.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Translate `FlightService.cs:1380-1440` into a `FlightStateTransitions` map.
- [ ] Build the transition service.
- [ ] Cover every legal transition with a test.
- [ ] Cover key illegal transitions (DeliveryBooked → *; NotProcessed → Locked; etc.) with rejection tests.
- [ ] Integrate with audit log.

## Notes
L because the transition matrix is non-trivial (~30 legal transitions, must mirror legacy exactly). Tasks split it; aim for a small `transitions.yml` or in-code table.

ExcludedFromDeliveryProcess(99) is the side-branch — Valid/Locked/DeliveryPrepared/DeliveryPreparationError → Excluded → Valid. Preserve.
