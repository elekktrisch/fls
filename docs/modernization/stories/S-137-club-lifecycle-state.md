---
id: S-137
title: Deployment entity + lifecycle state machine + job filter (ADR 0018)
epic: E-15
status: todo
depends_on: [S-048]
acceptance:
  - New `Deployment` entity exists in Postgres with columns: `id` (UUID), `name`, `lifecycle_state` (enum), `trial_started_at` (nullable timestamptz), `billing_customer_id` (nullable text), `billing_subscription_id` (nullable text), `plan` (enum `{free, active}`, default `free`), audit timestamps. Flyway migration adds the table.
  - `Club` gains a non-null `deployment_id` UUID FK referencing `Deployment(id)`. The pre-existing tenancy contract holds: `@TenantId` stays on Club (per ADR 0008); cross-Club isolation inside one Deployment is preserved.
  - `lifecycle_state` enum: `{ sandbox, trial, active, past_due, cancelled, deleting }`. Lives on Deployment only (NOT on Club).
  - `DeploymentLifecycleStateMachine` domain service encapsulates legal transitions per ADR 0018: `(none) → trial` on first successful ingest (S-138); `trial → active` on subscription activation (S-145); `active → past_due` on payment failure; `past_due → cancelled` after dunning grace; `cancelled → deleting` on deletion request OR after grace; `trial → deleting` at the 72 h mark (S-142). Illegal transitions throw `IllegalLifecycleTransitionException`.
  - `DeploymentContext` service enumerates child Clubs for cross-cutting reads (bulk-provision, trial-delete cascade, freemium-caps evaluator) inside an `UnscopedTenantContext` window (S-023).
  - Scheduled-job framework (S-081) gets a `@LifecycleStateFilter({ ACTIVE })` annotation (refine syntax). All existing job classes (S-083+) are tagged: DailyFlightValidation / DailyReport / LicenceNotification / PlanningDayNotification / DeliveryCreation / DeliveryMailExport / AircraftStatReport → `ACTIVE`; SandboxReset → `SANDBOX`. Jobs iterate Deployments first, then resolve their Clubs via `DeploymentContext`.
  - Admin endpoint `POST /api/v1/admin/deployments/{deploymentId}/lifecycle` (system-admin only) transitions a Deployment manually (used for operator-owned tenants: provision via ingest → flip to `active`).
  - Audit-log emits `deployment.lifecycle_transition` on every state change with from / to / actor / Deployment ID.
  - Cross-tenant leakage CI test (S-024) extended to assert (a) the Club `@TenantId` boundary holds, AND (b) the lifecycle filter applies (jobs don't touch `sandbox` / `deleting` Deployments unless explicitly tagged).
estimate: M
adr_refs: [0008, 0018]
parity_test: tests/tenancy/deployment-lifecycle.spec.ts (new)
---

## Context
Vision C31 + C34 introduce the Deployment entity as the parent of 1..N Clubs. One legacy FLS upload bundle = one Deployment containing the Clubs that were in that legacy install. The trial countdown, the subscription IDs, the freemium plan, and the lifecycle state all live on Deployment; Club stays the `@TenantId` carrier so cross-Club isolation is preserved (per user choice — see C34).

This story owns the entity, the FK, the state machine, the `DeploymentContext` for cross-Club iteration, the job-filter annotation, and the audit-event emission. Stories that *consume* the Deployment (S-138, S-141, S-142, S-145, the scheduled jobs, S-143 gates) get a clean API instead of inlining transition logic.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Flyway migration: add `deployment` table + `club.deployment_id` FK.
- [ ] `Deployment` JPA entity + repository.
- [ ] `DeploymentLifecycleStateMachine` domain service in the Deployment aggregate.
- [ ] `DeploymentContext` service: enumerate Clubs for a Deployment under `UnscopedTenantContext`.
- [ ] `@LifecycleStateFilter` annotation + Spring `@Scheduled` aspect.
- [ ] Admin endpoint for manual transitions.
- [ ] Backfill existing scheduled-job stories (S-081+) with the annotation as amendments at refine-time.
- [ ] Unit tests for legal + illegal transitions.

## Notes
- `lifecycle_state` is stored on Deployment, but the *transition* logic is a domain service (per primary directive 2: business logic in DDD, not the schema). The schema's only enforcement is enum-literal.
- `deleting` is terminal-then-hard-deleted: when the cascade fires (S-142), the Deployment + its Clubs + every tenant-scoped row vanish. There is no `deleted` state.
- `plan` (`free` / `active`) is derived from `lifecycle_state` (`trial` + `active` map to `active`; `sandbox` / `cancelled` map to `free`; `past_due` retains read access but blocks gated writes — refine via ADR 0020).
