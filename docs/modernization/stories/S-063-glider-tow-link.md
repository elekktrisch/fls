---
id: S-063
title: Glider↔Tow link integrity (TowFlightId recursion in validation + cascade)
epic: E-07
status: in_progress
started_at: 2026-05-25
github_issue: 130
github_pr: 131
depends_on: [S-062a]
acceptance:
  - When a glider flight has `start_type=Towing`, a linked tow Flight is required (1:1 via `tow_flight_id`).
  - Validation of the glider flight recurses through `tow_flight_id` — both must be valid for the glider to reach Valid.
  - Updating one side of the pair (e.g. crew on the tow plane) preserves the link.
  - Cascade semantics on tow row when the glider is deleted: tow row is also deleted (or unlinked — confirm legacy behavior in legacy `FlightService.Delete`).
  - Depth tests cover: partial update on glider while tow is referenced; orphaned tow flights; tow flight without a glider.
estimate: M
adr_refs: [0008]
parity_test: alpenflight/server/.../FlightsTowLinkIT.java + FlightsControllerConcurrencyIT.delete_cascade_rollsBack_whenTowIsTerminal; Playwright depth in S-105
refined: true
refined_at: 2026-05-25
refined_specialists: [requirements, solution, qa]
---

## Context
Sacred-cow shape; legacy specs do not exercise it (R14 callout). Get the cascade wrong and orphan rows accumulate.

## Parity divergence (intentional)
**Validation recursion couples glider to tow.** AlpenFlight glider validity now adds sentinel `VALIDATION_ERROR_Tow_flight_invalid` whenever the linked tow has any per-flight error; missing or tombstoned tow gets `VALIDATION_ERROR_Tow_flight_missing_or_deleted`. Legacy `FlightService.cs:987-1015` keeps the pair independently valid — a glider could read Valid while its tow was Invalid. AlpenFlight prefers one verdict per pair to spare the operator cross-row reasoning. Operator-approved 2026-05-25.

**Partial-PUT contract.** `FlightUpdateRequest.towFlightId` absent → preserve existing link (legacy default was silent unlink — the bug this story fixes). Explicit unlink uses the new `unlinkTowFlight: true` flag. Wire-shape note: the refinement called for `JsonNullable<UUID>`, but jackson-databind-nullable targets Jackson 2 and this stack is on Jackson 3 (`tools.jackson.core:3.1.2`); the boolean-sentinel pattern is the Jackson-3-compatible equivalent.

**Double-link rejection.** Two gliders linking the same tow row is now rejected (cascade-delete of glider1 would otherwise silently orphan the second link). Legacy permits it implicitly; AlpenFlight enforces 1:1 via `TowLinkPolicy.verifyExclusiveLink`.

## Cross-story contracts
- **S-077** (rules-engine glider→tow recursion) wraps `FlightCompositeValidator`; no reimplementation.
- **S-083** (DailyFlightValidationJob) calls `FlightCompositeValidator` per glider; dedupes referenced tow rows at the job level.
- **S-105** owns the Playwright depth corpus.
- **S-161** (charter visibility) unaffected; `FlightLookup` honors `@TenantId` so cross-club tow rows can't leak into a glider's validation.

## Out of scope
- Orphan-tow sweep job (legacy doesn't sweep; not a regression). Future operational-hygiene story if it ever surfaces.
- Cross-club aerotow billing — S-161.
- Inline FE form validation (operator-requested during refine) — separate follow-up FE story.
