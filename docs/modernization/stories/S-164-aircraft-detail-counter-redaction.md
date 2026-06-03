---
id: S-164
rolled_up_into: J-1
title: Redact `latestCounter` from Aircraft detail GET for non-manager callers
epic: E-07
status: todo
estimate: S
depends_on: [S-058]
origin: rework-meta
origin_story: S-058
kind: deferred-hardening
adr_refs: [0008, 0022]
parity_test: none
refined: false
---

## Context

S-058 makes Aircraft detail (`GET /api/v1/aircraft/{id}`) readable by any
authenticated user (cross-tenant picker support for the charter case). The
DTO surfaces `latestCounter` inline as a convenience for the manager's
own dashboard. The dedicated counter-list endpoint
(`/api/v1/aircraft/{id}/operating-counters`) is correctly gated to the
manager-side `AircraftAccess.canOperate` predicate — but the inline
`latestCounter` on detail isn't.

The result: a non-manager caller (Club B opening the picker on Club A's
aircraft) sees the latest engine / flight-hour counter inline, even though
they cannot list the counter history. That's an inconsistency, not a
critical leak — counter values aren't strongly sensitive — but the policy
bar deserves to be flat.

## Acceptance criteria (placeholder until refined)

- For callers who do not satisfy `AircraftAccess.canOperate` on the target
  aircraft, the `latestCounter` field on `AircraftDetail` is null (or
  omitted via a separate non-manager projection).
- Manager-side callers see `latestCounter` unchanged.
- A test pins the new behaviour: cross-tenant caller reads detail → no
  counter; same-club FLIGHT_OPERATOR caller reads detail → counter
  present.

## Notes

- Two implementation shapes to weigh at refine:
  1. Caller-aware mapper — single `AircraftDetail` DTO, null out the field
     in the mapping layer when the predicate fails.
  2. Two projection DTOs — `AircraftDetailManagerView` vs
     `AircraftDetailPublicView`, returned conditionally by the controller.
- (1) is cheaper; (2) is cleaner if more "manager-only" fields surface
  later (e.g. flight-count breakdowns, financial metadata). Pick at refine.
