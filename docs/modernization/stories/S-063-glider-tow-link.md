---
id: S-063
title: Glider↔Tow link integrity (TowFlightId recursion in validation + cascade)
epic: E-07
status: todo
depends_on: [S-062]
acceptance:
  - When a glider flight has `start_type=Towing`, a linked tow Flight is required (1:1 via `tow_flight_id`).
  - Validation of the glider flight recurses through `tow_flight_id` — both must be valid for the glider to reach Valid.
  - Updating one side of the pair (e.g. crew on the tow plane) preserves the link.
  - Cascade semantics on tow row when the glider is deleted: tow row is also deleted (or unlinked — confirm legacy behavior in legacy `FlightService.Delete`).
  - Depth tests cover: partial update on glider while tow is referenced; orphaned tow flights; tow flight without a glider.
estimate: M
adr_refs: [0008]
parity_test: tests/flights/05-flights-edit.spec.ts (smoke); depth in S-105
---

## Context
Sacred-cow shape; legacy specs do not exercise it (R14 callout). Get the cascade wrong and orphan rows accumulate.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Validation recursion through the self-FK.
- [ ] Cascade definition: study legacy `FlightService.Delete` to confirm whether tow rows are deleted, unlinked, or preserved. Match.
- [ ] Tests covering the cascade + recursion.

## Notes
This is the kind of behavior that's easy to half-implement and not notice until a real-world flight produces an orphan. Be thorough on the tests; S-105 expands them further.
