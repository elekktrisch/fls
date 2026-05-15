# 0009 — Background-job mechanism

- **Status:** Accepted
- **Date:** 2026-05-14
- **Decision criteria** (from [vision §6](../02-vision-and-constraints.md#6-decision-criteria-for-phase-3)): solo-operator operability · mature ecosystem · preserves sacred cows (rules engine + delivery pipeline) · lower TCO

## Context

The current system runs ~8 scheduled jobs ([current-state §2 → "Email & scheduled jobs"](../01-current-state.md#email--scheduled-jobs)): daily flight validation, daily report, monthly aircraft stats, planning-day notifications, licence-expiry warnings, delivery creation (rules engine), delivery mail export, aircraft DB sync. Trigger mechanism today is OS cron → `FLS.Workflow.Activator` console app → bearer-token-authenticated `GET /api/v1/workflows/<name>` → in-process dispatch via `WorkflowService.Run()`, with routing by UTC hour ([server.md insight 1](../../legacy/server.md)). The `job_scheduling_data_2_0.xsd` in the source tree is a dead Quartz schema.

For a single-instance Spring Boot deployment ([ADR 0010](.) — pending, but the soft preference for "fewer moving parts" makes single-instance the default), the simplest model is in-process scheduling. The current external-cron-to-HTTP architecture exists primarily to avoid an in-process scheduler in the .NET app; in Spring Boot we have one for free.

## Options considered

### Option A — Spring `@Scheduled` in-process
- **Capabilities:** `@Scheduled(cron = "...")` or `@Scheduled(fixedRate = ...)` annotations on `@Component` methods. Spring's `TaskScheduler` runs jobs on a configurable thread pool. Cron expressions are the standard cron-with-seconds format. Job methods are normal `@Transactional` Spring code — same DI, same EntityManager, same audit-log infrastructure as request handlers.
- **Fit to criteria:** operability ✓ (one process, one config, one place to look for failures). Mature ecosystem ✓ (Spring-native, documented, ubiquitous). Lower TCO ✓ (no extra infrastructure).
- **Migration cost:** low — each existing job becomes one Spring `@Component` with a `@Scheduled` method. Triggering "manually" (the current "curl the workflow endpoint" pattern) can be preserved with a thin admin endpoint that calls the same method, but it's no longer required.
- **Ecosystem risk:** low.
- **Escape hatch:** swap to Quartz or extract to a separate scheduler-binary later; per-job refactor.
- **Multi-instance caveat:** if the deploy ever becomes HA (multiple replicas), each replica fires `@Scheduled` jobs independently. Mitigation: add ShedLock or net.javacrumbs.shedlock with the Postgres provider — adds a `shedlock` table and an annotation. Trivial. Not needed for single-instance.

### Option B — Quartz Scheduler via `spring-boot-starter-quartz`
- **Capabilities:** Quartz-backed scheduling with persistence in DB tables (`QRTZ_*`), native clustering (multiple instances coordinate automatically), misfire policies, persistent triggers across restarts.
- **Fit to criteria:** operability ~ (more concepts, more tables). Mature ecosystem ✓. Lower TCO ✓.
- **Migration cost:** medium — Quartz job classes, trigger configuration, DB-schema additions.
- **Ecosystem risk:** low — Quartz is long-running and mature, though development has slowed.
- **Why not chosen:** the feature ceiling — persistent triggers, clustering, misfire policies — isn't justified by ~8 nightly jobs at a single-instance scale. ShedLock-on-top-of-@Scheduled handles the only Quartz benefit we might want (multi-instance coordination) with far less ceremony.

### Option C — Keep external cron → HTTP endpoint → in-process dispatch
- **Capabilities:** mirrors the current architecture exactly. Cron-on-host runs `curl -H "Authorization: Bearer ..." .../workflows/...`.
- **Fit to criteria:** operability ✗ (two systems to keep in sync — cron file and app code).
- **Why not chosen:** the only reason the current system uses this shape is to avoid an in-process scheduler in the .NET app. Spring Boot has one built in; there's no reason to keep the extra moving part.

### Option D — Dedicated job runner (Spring Batch, Temporal)
- **Capabilities:** Spring Batch for batch-shaped step pipelines; Temporal for durable workflows with retries / human-in-the-loop / long-running orchestration.
- **Why not chosen:** the workload here is small, repetitive, and well-structured. Spring Batch is for ETL-shaped work; Temporal is for distributed workflows with operator intervention. Both are massive over-engineering for our ~8 nightly cron-style jobs.

## Decision

Chosen: **Option A — Spring `@Scheduled` in-process**. Best fit for criteria 7 and 10 simultaneously: one process, no extra infrastructure, no extra DB tables, no extra config files. Triggers stay close to the code they exercise; the operator can grep one folder for all scheduled work. ShedLock is the escape hatch if HA becomes a requirement later.

## Consequences

- **Positive:**
  - Single moving part replaces today's "cron file + Workflow.Activator + bearer-token + HTTP endpoint + WorkflowService.Run() router" stack.
  - Job code reuses normal Spring infrastructure — `@Transactional`, dependency injection, the same EntityManager, the same audit-log mechanism, the same observability hooks.
  - Adding a new job is a single `@Component` with a `@Scheduled` annotation — no controller, no router, no external scheduler.
  - "Run a job manually" remains easy via an admin endpoint that invokes the same method — preserves the operator's ability to backfill / re-run.
  - The current hour-based dispatcher (`WorkflowService.Run()` UTC-hour routing) goes away in favor of explicit cron expressions per job.

- **Negative:**
  - In-process scheduling ties job execution to application uptime. Mitigation: monitoring will catch missed runs (observability ADR); jobs are designed to be idempotent so a missed run can be re-triggered safely.
  - Multi-instance deploy without ShedLock would double-fire jobs. Mitigation: explicit story to add ShedLock if/when we scale beyond one replica.
  - Long-running jobs (delivery mail export, monthly aircraft stat report) tie up a scheduler thread. Mitigation: use the async/`@Async` annotation or a dedicated thread pool config for long-running jobs.

- **Follow-ups (other ADRs / stories implied):**
  - **Story:** port each existing job ([current-state §2](../01-current-state.md#email--scheduled-jobs)) to a Spring `@Scheduled` component. Idempotency review per job — every job must be safe to re-run.
  - **Story:** define the time-zone policy — jobs today use UTC hour routing; the new system should be explicit about whether cron expressions are UTC or Europe/Zurich (recommend UTC in code, document the local-equivalent firing time for ops).
  - **Story:** preserve the "trigger this job manually" capability via an admin endpoint (admin role required) that calls the job's `runOnce()` method. Useful for backfill and rules-engine debugging ([R3](../01-current-state.md#r3--accounting-rules-engine-parity-critical-customer-configurable)).
  - **Story:** add ShedLock + a `shedlock` table to the Flyway baseline as a stub even if single-instance — switching it on is then a config flip.
  - **Story:** observability — every scheduled job emits `started`, `completed`, `failed`, with duration metric; alerts on consecutive failures. Feeds [ADR 0011](.).
  - **Story:** test harness — Spring Boot test that triggers a job's `runOnce()` against a clean test DB and asserts the resulting state. Necessary for the delivery-creation job whose parity is sacred ([R3](../01-current-state.md#r3--accounting-rules-engine-parity-critical-customer-configurable)).
  - **Story:** decommission `FLS.Workflow.Activator` (its replacement = Spring's internal scheduler).
