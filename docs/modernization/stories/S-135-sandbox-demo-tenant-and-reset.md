---
id: S-135
title: Sandbox demo Deployment — seed data + nightly reset
epic: E-15
status: todo
depends_on: [S-047, S-048, S-049, S-050, S-051, S-058, S-068, S-081, S-137]
acceptance:
  - A single Deployment row with `lifecycle_state = sandbox` and a fixed UUID exists in seed data.
  - The sandbox Deployment contains 1..N seeded Clubs (refine — start with 1 representative club, add a 2nd if multi-club demo is useful) and is pre-populated with realistic Swiss-club-shaped synthetic data: ≥ 3 aircraft (mixed glider + tow + motor), ≥ 10 persons, ≥ 5 locations, ≥ 30 flights spanning the last 30 days, ≥ 10 reservations spanning the next 14 days, ≥ 1 planning day. Seed fixtures live in `next/database/seed/sandbox/`.
  - `SandboxResetJob` (Spring `@Scheduled`, runs nightly at 03:00 Europe/Zurich per vision §2 NFR; tagged `@LifecycleStateFilter({ SANDBOX })`) truncates all rows belonging to the sandbox Deployment's child Clubs and re-seeds. Completes in < 5 min. Idempotent.
  - Reset job filters strictly: only rows whose `club_id` resolves to the sandbox Deployment are affected. A unit test asserts no other Deployment's rows can be reached.
  - Sandbox Deployment is excluded from real-Deployment scheduled jobs via the `@LifecycleStateFilter` mechanism from S-137.
  - Anonymous demo sessions (S-136) write to the sandbox Deployment's Clubs; writes are not preserved across reset.
estimate: M
adr_refs: [0008, 0018]
parity_test: tests/sandbox/reset.spec.ts (new)
---

## Context
Vision §8 demo-mode open item, resolved by C27 + C34: a sandbox Deployment that accepts anonymous writes and resets nightly. The Deployment is structurally indistinguishable from a real one — same `@TenantId` plumbing on its Clubs, same lifecycle column on the Deployment — distinguished only by `lifecycle_state = sandbox`.

Realistic seed data is a soft preference (vision §4): plausible Swiss club names, CH-registered aircraft, realistic flight durations. A prospective evaluator should see something that looks like their world.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Sandbox Deployment + Club(s) in `next/database/seed/sandbox/`.
- [ ] Per-entity seed fixtures (NDJSON or SQL inserts — refine).
- [ ] `SandboxResetJob` via Spring `@Scheduled(cron)` + `UnscopedTenantContext` (S-023) + `DeploymentContext` (S-137) for cascading the truncate over all child Clubs.
- [ ] Unit test asserting the reset can't touch non-sandbox rows.
- [ ] Integration test: seed sandbox, mutate via API, fire reset, assert restored.

## Notes
- Seed fixtures are committed (small; signal for evaluators is the realism). NOT a re-runnable extraction (memory `[[feedback-re-runnable-over-frozen-docs]]` applies to legacy-schema parity, not greenfield demo seed).
- Some seed data should be deliberately illustrative of the upgrade path: e.g. include a couple of flights that would trip a feature gate on the free tier, so the demo surfaces the upgrade prompt naturally.
