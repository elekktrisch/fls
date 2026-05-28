---
id: S-137
title: Deployment entity + lifecycle state machine + job filter (ADR 0018)
epic: E-15
status: done
started_at: 2026-05-28
done_at: 2026-05-28
merged: true
merged_at: 2026-05-28
github_issue: 159
github_pr: 160
depends_on: [S-048]
acceptance:
  - New `Deployment` entity exists in Postgres with columns: `id` (UUID), `name`, `owner_keycloak_sub` (UUID NOT NULL — the KC user who provisioned the Deployment via S-138), `lifecycle_state` (enum), `trial_started_at` (nullable timestamptz), `billing_customer_id` (nullable text), `billing_subscription_id` (nullable text), `plan` (enum `{free, active}`, default `free`), audit timestamps. Flyway migration adds the table.
  - Partial UNIQUE index `ux_deployment_owner_active ON deployment (owner_keycloak_sub) WHERE lifecycle_state IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'CANCELLED')` — one user holds at most one non-terminal Deployment. The `deleting` and `sandbox` states are exempt (data is going / shared-fixed-Deployment). Closes the second-ingest race structurally; consumed by S-138's 409 gate.
  - `Club` gains a non-null `deployment_id` UUID FK referencing `Deployment(id)`. The pre-existing tenancy contract holds: `@TenantId` stays on Club (per ADR 0008); cross-Club isolation inside one Deployment is preserved.
  - `lifecycle_state` enum: `{ sandbox, trial, active, past_due, cancelled, deleting }`. Lives on Deployment only (NOT on Club).
  - Lifecycle FSM encapsulates legal transitions per ADR 0018: `(none) → trial` on first successful ingest (S-138); `trial → active` on subscription activation (S-145); `active → past_due` on payment failure; `past_due → cancelled` after dunning grace; `cancelled → deleting` on deletion request OR after grace; `trial → deleting` at the 72 h mark (S-142). Illegal transitions throw `IllegalLifecycleTransitionException`.
  - `DeploymentContext` service enumerates child Clubs for cross-cutting reads (bulk-provision, trial-delete cascade, freemium-caps evaluator) under a per-Club tenant scope.
  - `@LifecycleStateFilter({ ACTIVE })` annotation pairs with `@Scheduled`; AOP advice scopes the job body to Deployments matching the filter. ArchUnit rule enforces the pairing.
  - Admin endpoint `POST /api/v1/admin/deployments/{deploymentId}/lifecycle` (system-admin only) transitions a Deployment manually (used for operator-owned tenants: provision via ingest → flip to `active`).
  - Audit-log emits `deployment.lifecycle_transition` on every state change with from / to / actor / Deployment ID.
  - Cross-tenant leakage CI test (S-024) extended to assert (a) the Club `@TenantId` boundary holds, AND (b) the lifecycle filter applies (jobs don't touch `sandbox` / `deleting` Deployments unless explicitly tagged).
estimate: M
adr_refs: [0008, 0018, 0022, 0023]
parity_test: tests/tenancy/deployment-lifecycle.spec.ts (new)
integration_base: integration/migration
refined: true
refined_at: 2026-05-28
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer, performance-engineer]
---

## Context
Vision C31 + C34 introduce the Deployment entity as the parent of 1..N Clubs. One legacy FLS upload bundle = one Deployment containing the Clubs that were in that legacy install. Trial countdown, subscription IDs, freemium plan, and lifecycle state live on Deployment; Club stays the `@TenantId` carrier so cross-Club isolation inside one Deployment is preserved.

This story owns the entity, the FK, the FSM, the `DeploymentContext` for cross-Club iteration, the job-filter annotation, and audit-event emission. Stories that *consume* the Deployment (S-138 trial provisioning, S-141 ingest, S-142 cleanup, S-145 billing, S-143 freemium gates, the scheduled jobs) get a clean API instead of inlining transition logic.

<!-- modernize-refine: start -->

## Carried-forward decisions

Decisions still load-bearing for downstream stories:

- **Sandbox-immutable + admin recovery edge.** Sandbox-source transitions throw `sandbox_immutable` (404-safe stable code). `deleting → cancelled` is allowed inside the grace window for accidental-deletion rescue.
- **Operator-Deployment is the structural backfill convention.** The V14 migration creates a deterministic operator Deployment (UUID `…0002`); every pre-S-137 Club + every direct-JDBC INSERT that doesn't supply `deployment_id` lands under it via the column DEFAULT. S-138's trial-ingest path overrides this by passing the user's TRIAL Deployment id explicitly via `Club.create`.
- **Operator-bypass = manual admin flip.** Self-service ingest (S-138) always lands `TRIAL`. Operator-owned tenants go through the same provisioning path then call this story's admin endpoint to flip to `ACTIVE`. No branching inside the provisioning service.
- **Partial UNIQUE excludes `sandbox` + `deleting`.** S-138's second-ingest 409 gate consumes the `{TRIAL, ACTIVE, PAST_DUE, CANCELLED}` predicate. `deleting` (data going) and `sandbox` (shared-fixed singleton) are exempt by design — a user with a `DELETING` Deployment may legitimately re-ingest while the cleanup cascade runs.
- **`(none) → trial` audit payload `from_state` is literal null.** Pin for downstream consumers — not the string `"none"`.
- **`@LifecycleStateFilter` + `@Scheduled` pairing is ArchUnit-enforced.** Every `@Scheduled` method must carry `@LifecycleStateFilter` with a non-empty state set. Missing annotation OR empty set = build break. Today's repo has no `@Scheduled` methods; the rule passes vacuously and lights up the moment S-083+ scheduled jobs land.
- **`Tenants.runAs` callers are ArchUnit-allowlisted.** Only `platform.tenancy` (carrier), `audit.application` (mutation listener), `audit.web` (request audit filter), and `deployments.application` (`DeploymentContext`) may call. Adding a caller is a deliberate security decision.
- **`Deployment.owner_keycloak_sub` immutability.** Final after `startTrial` / backfill; JPA `updatable = false`. Transfer-deployment-to-other-user is an explicit non-feature (future security-engineer review before any mutator lands).
- **Out:** bulk admin operations; cleanup-cascade worker (S-142); KC group/role lifecycle (S-138 provisioning + S-142 teardown); freemium read-side gating (S-143); upgrade UI banners (S-144).

## Parity strategy

N/A — greenfield SaaS shape.

<!-- modernize-refine: end -->
