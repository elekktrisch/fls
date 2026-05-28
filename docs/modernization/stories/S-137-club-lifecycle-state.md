---
id: S-137
title: Deployment entity + lifecycle state machine + job filter (ADR 0018)
epic: E-15
status: in_progress
started_at: 2026-05-28
github_issue: 159
depends_on: [S-048]
acceptance:
  - New `Deployment` entity exists in Postgres with columns: `id` (UUID), `name`, `owner_keycloak_sub` (UUID NOT NULL — the KC user who provisioned the Deployment via S-138), `lifecycle_state` (enum), `trial_started_at` (nullable timestamptz), `billing_customer_id` (nullable text), `billing_subscription_id` (nullable text), `plan` (enum `{free, active}`, default `free`), audit timestamps. Flyway migration adds the table.
  - Partial UNIQUE index `ux_deployment_owner_active ON t_deployment (owner_keycloak_sub) WHERE lifecycle_state IN ('trial', 'active', 'past_due', 'cancelled')` — one user holds at most one non-terminal Deployment. The `deleting` and `sandbox` states are exempt (data is going / shared-fixed-Deployment). Closes the second-ingest race structurally; consumed by S-138's 409 gate.
  - `Club` gains a non-null `deployment_id` UUID FK referencing `Deployment(id)`. The pre-existing tenancy contract holds: `@TenantId` stays on Club (per ADR 0008); cross-Club isolation inside one Deployment is preserved.
  - `lifecycle_state` enum: `{ sandbox, trial, active, past_due, cancelled, deleting }`. Lives on Deployment only (NOT on Club).
  - `DeploymentLifecycleStateMachine` domain service encapsulates legal transitions per ADR 0018: `(none) → trial` on first successful ingest (S-138); `trial → active` on subscription activation (S-145); `active → past_due` on payment failure; `past_due → cancelled` after dunning grace; `cancelled → deleting` on deletion request OR after grace; `trial → deleting` at the 72 h mark (S-142). Illegal transitions throw `IllegalLifecycleTransitionException`.
  - `DeploymentContext` service enumerates child Clubs for cross-cutting reads (bulk-provision, trial-delete cascade, freemium-caps evaluator) inside an `UnscopedTenantContext` window (S-023).
  - Scheduled-job framework (S-081) gets a `@LifecycleStateFilter({ ACTIVE })` annotation (refine syntax). All existing job classes (S-083+) are tagged: DailyFlightValidation / DailyReport / LicenceNotification / PlanningDayNotification / DeliveryCreation / DeliveryMailExport / AircraftStatReport → `ACTIVE`; SandboxReset → `SANDBOX`. Jobs iterate Deployments first, then resolve their Clubs via `DeploymentContext`.
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

<!-- modernize-refine: start -->

## Design notes

Foundational. New `deployments` Modulith module (sibling of `clubs`), inner `domain` / `application` / `web` / `infra` per ADR 0023. Lifecycle is a sacred-cow concern crossing every module — earns its own module rather than living inside `clubs`.

- **FSM = methods on the `Deployment` aggregate.** Per ADR 0022 D2: `Deployment.startTrial(Clock, manifestName, ownerSub)`, `Deployment.activateSubscription(customerId, subId)`, `Deployment.markPastDue()`, `Deployment.cancel()`, `Deployment.scheduleDelete(Clock)`, `Deployment.expireTrial(Clock)`. Each asserts legal-from-state and throws `IllegalLifecycleTransitionException`. The AC wording "domain service" is a paraphrase; the shape is aggregate-method. A `private static final Map<LifecycleState, Set<LifecycleState>> LEGAL_TRANSITIONS` on `Deployment` keeps the legal-edges table greppable.
- **Aggregate-event audit emission.** `Deployment` exposes a `@DomainEvents` collection accumulating `DeploymentLifecycleTransitioned(deploymentId, fromState, toState, occurredAt)`. S-027's audit listener subscribes. Actor + correlationId come from `SecurityContextHolder` / MDC at listener-time (NOT stuffed into the event payload — keeps the domain event tenant-context-free). `@AfterDomainEventPublication` clears the buffer.
- **`@TenantId` placement unchanged.** Stays on `Club` (ADR 0008). `Deployment` carries NO `@TenantId` — it's a tenancy-parent, queried unscoped. Add `Deployment` to ADR 0008's whitelist of cross-tenant-readable aggregates at implement time. `Club.deploymentId` is a plain `UUID` field, NOT a `@ManyToOne` — keeps the Club aggregate boundary tight; avoids lazy-init surprises under tenant-filtered reads.
- **`DeploymentContext.forEachClub(deploymentId, Consumer<Club>)`.** Opens an `UnscopedTenantContext` window (S-023 try-with-resources), enumerates Club rows by `deployment_id` via projection (NOT a `@OneToMany` collection load — avoids N+1 + Cartesian risk), then for each Club sets the tenant via `CurrentTenantIdentifierResolver` + invokes the body inside a nested scoped block. Also exposes `findDeployment(LifecycleState... states)` and `forEachActiveDeployment(Consumer<Deployment>)` for job scheduling. ArchUnit FQN allowlist limits null-tenant-window opening to this class only.
- **`@LifecycleStateFilter(LifecycleState... states)` API.** Varargs over Set for ergonomics; aspect normalises to `EnumSet`. Spring AOP `@Around` advice targets methods carrying both `@Scheduled` AND `@LifecycleStateFilter`. **Operator-grilled 2026-05-28: ArchUnit-enforced — every `@Scheduled` class MUST carry `@LifecycleStateFilter` with a non-empty state set. Missing annotation OR empty set = build break.** Cross-cutting ops jobs declare all states explicitly. Defends against new jobs forgetting + leaking into `sandbox` (stranger data) or `deleting` (cascade-in-flight).
- **Backfill operator KC sub: env-driven Flyway placeholder.** Operator-grilled 2026-05-28. Flyway reads `${alpenflight.operator.keycloak-sub}` via Spring placeholders, creates one "operator" Deployment (`name='operator'`, `lifecycle_state='active'`, `plan='active'`), then `UPDATE t_club SET deployment_id = <that-id>`. Migration fails loud if env unset in prod; dev/test get a placeholder via `application-dev.yml`. Avoids the sentinel-then-flip two-step.
- **Sandbox Deployment provisioning.** Flyway seed creates singleton row with fixed UUID `00000000-0000-0000-0000-000000000001`, `lifecycle_state='sandbox'`, `owner_keycloak_sub='00000000-0000-0000-0000-000000000000'` (sentinel — partial UNIQUE excludes `sandbox` so no collision). Exposed as `Deployment.SANDBOX_ID` Java constant. S-135 references by ID.
- **`plan` is stored, not generated.** AC notes say derived; pin: aggregate methods write `plan` atomically with state on each transition. NO generated column (ADR 0022 D2 forbids). The reconciliation invariant `assertPlanConsistent()` is called at end of each transition.
- **Admin endpoint.** `POST /api/v1/admin/deployments/{id}/lifecycle` body `{targetState}`. `@PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")`. 404 on unknown ID (don't leak existence). 409 on illegal transition OR sandbox target. Returns 200 with updated Deployment DTO.
- **Schema deviation from ADR 0022 D2: none.** `lifecycle_state` is a text column (enum-backed in Java); state-transition rules live in aggregate methods. Partial UNIQUE is identity-structural. NO CHECK enumerating valid states.

## Edge cases & hidden requirements

- **Backfill operator KC sub env var name.** Suggest `ALPENFLIGHT_OPERATOR_KEYCLOAK_SUB` mirroring the existing realm-bootstrap convention; implementer greps `keycloak` bootstrap in `alpenflight/server` to match the canonical name.
- **`@LifecycleStateFilter` semantics.** Aspect runs at `@Scheduled` tick: `DeploymentContext.findDeployment(annotation.states())` → for each Deployment, `forEachClub(...)`. Skipped Deployments emit a DEBUG log (not INFO — dunning-mode noise discipline).
- **`startTrial()` is safe to call pre-commit.** Phase A of S-138's ingest txn requires this: pure aggregate construction; audit-row emission rides the same txn's domain-event listener. NO external side effects (KC, mail, telemetry) inside the transition method.
- **`deleting` jobs skip silently, never throw.** No job opts into `deleting`; cascade is owned by S-142's dedicated worker. Filter aspect `return`s for off-list states.
- **`past_due` is fully mutable at the Deployment level.** Plan stays `active` on `active → past_due`; feature gates (S-143) apply per-plan. UI banner is S-144's surface.
- **Admin endpoint refuses sandbox; allows `deleting → cancelled` recovery.** Within the grace window admin can rescue an accidentally-deleted Deployment. Sandbox is immutable: any target → 409 `sandbox_immutable`.
- **`(none) → trial` audit payload `from_state` is literal null**, not the string `"none"`. Pin for downstream consumers.
- **Optimistic locking.** Concurrent admin flips OR admin-flip-vs-cron race → JPA `@Version` on `Deployment`. State machine re-reads state inside the txn before validating, doesn't trust the request payload's "current state."
- **`Deployment.owner_keycloak_sub` immutability.** Final after `startTrial` / backfill (mirrors `User.keycloakSub`). NO aggregate mutator; field is JPA `updatable = false`. Transfer-deployment-to-other-user is an explicit non-feature (future security review).
- **Out:** bulk admin operations; cleanup-cascade worker (S-142); KC group/role lifecycle (S-138 owns provisioning, S-142 owns teardown); freemium read-side gating (S-143); upgrade UI banners (S-144).

## Security plan

- **Admin endpoint.** `@PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")` — cross-Deployment surface, no tenant gate. 404 on unknown ID (defense vs. token-leak probing). 409 on illegal transition; sandbox target → 409 `sandbox_immutable`.
- **`DeploymentContext` ArchUnit allowlist.** Mirrors S-138's `MigrationIngestTenantContext` pin. Only `tenancy.context.DeploymentContext` (FQN) may open the `UnscopedTenantContext` for cross-Club iteration. Build break if any other class under `tenancy.*` or downstream calls `UnscopedTenantContext.open()` without being on the list.
- **Sandbox Deployment defense in depth.** (a) admin endpoint short-circuits with 409 before invoking the FSM; (b) `Deployment.lifecycleTransition(...)` asserts `if (this.lifecycleState == SANDBOX) throw IllegalLifecycleTransitionException`; (c) ArchUnit asserts the sandbox UUID literal is referenced ONLY by `SandboxResetJob` + the assertion guard.
- **`@LifecycleStateFilter` ArchUnit rule.** Any class with a `@Scheduled` method MUST carry `@LifecycleStateFilter` with a non-empty state set. Missing OR empty = build break. Operator-grilled — defends against new jobs accidentally iterating `sandbox`/`deleting`.
- **`Deployment.owner_keycloak_sub` immutability.** Final after `startTrial`/backfill. JPA `updatable = false` + ArchUnit forbids setter generation.
- **Audit event PII.** `deployment.lifecycle_transition` carries `{deploymentId, from_state, to_state, actor.{sub, email}, timestamp}`. NO `Deployment.name` (operator-customised free-text per S-138 precedent). NO `billing_customer_id`/`billing_subscription_id` (PCI scope adjacency; S-145 owns billing audit). `actor.email` redacted at S-027 log-sink.
- **Cross-tenant leakage CI extension (S-024).** Two new sweep assertions: (a) Club `@TenantId` boundary holds across two Clubs sharing one Deployment; (b) `@LifecycleStateFilter` AOP advice does NOT fire job bodies against off-list states.
- **OWASP deltas.** A01 — admin endpoint SYSTEM_ADMINISTRATOR gate + sandbox guard. A04 — partial UNIQUE `ux_deployment_owner_active` is the structural one-Deployment-per-user defense. A09 — audit + filter-fired metric (labels `{deployment_state, job}`, NO `deploymentId` to bound cardinality); burst alerts deferred to S-041.

## Test plan

- **Unit (~18, parameterized).** FSM transition matrix: 6 states × 6 targets = 36 pairs; legal pairs assert new state + emitted domain event; illegal pairs assert `IllegalLifecycleTransitionException`. Sandbox-as-source illegal for every target. Plus `plan` derivation per state.
- **Integration (~8, Testcontainers Postgres).** Partial UNIQUE: `(owner=X, trial)` ok; second `(owner=X, trial)` → `DataIntegrityViolationException` (catch SQLSTATE 23505 at repo boundary); `(owner=X, sandbox)` ok; `(owner=X, deleting)` ok; `(owner=X, cancelled)` blocks second cancelled. `Club.deployment_id` FK NOT NULL + ON DELETE behaviour (assert RESTRICT — S-142 owns cascade). `DeploymentContext.forEachClub` tenant-scoping (callback fires exactly N times, `TenantContext.current()` flips per Club, no cross-Deployment leakage). `@LifecycleStateFilter` AOP advice: bean tagged `{ACTIVE, PAST_DUE}` invoked only against those states; empty filter = no Deployments (fail-closed). Audit event emission: exactly one event per legal transition with PII-clean payload.
- **Web slice (~4, `@WebMvcTest` + mocked SecurityContext).** Admin endpoint: SYSTEM_ADMINISTRATOR + legal → 200; + illegal → 409; non-admin → 403; sandbox target → 409. Verify controller short-circuits before invoking the state machine on authz failure (audit-noise discipline).
- **Migration test (1, Flyway Testcontainers).** Pre-S-137 Clubs seeded via prior migration; apply S-137; assert (a) operator Deployment with the migration's deterministic UUID exists, (b) sandbox Deployment with fixed UUID exists, (c) every pre-existing `t_club.deployment_id` = operator UUID, (d) partial UNIQUE present in `pg_indexes`.
- **Cross-tenant leakage CI extension (S-024 sibling).** Three new assertions: cross-Club query under context A returns zero rows from B (same Deployment); enumerate every `@Scheduled` class via classpath scan + parameterized test asserts off-list states produce zero side-effects (auto-enrols future jobs); assert every `@Scheduled` class carries `@LifecycleStateFilter` (matches the ArchUnit rule at runtime).
- **Parity strategy.** N/A — greenfield SaaS shape.
- **Risks.** (1) AOP advice + `@Scheduled` proxying — test via Spring-managed proxy, not direct call. (2) Modulith event-publication timing — async by default; either `@ApplicationModuleTest` `Scenario` API or pin publisher sync for audit slice. (3) Partial UNIQUE is Postgres-specific — fail build if `H2Dialect` on integration classpath (likely already enforced post-S-015). (4) Cross-tenant CI relies on classpath scan — anchor at modulith root + sentinel canary.

## Performance plan

- **Indexes required.** `ix_deployment_lifecycle ON t_deployment (lifecycle_state)` non-unique btree — drives every `@Scheduled` tick's `WHERE lifecycle_state IN (...)` filter scan. `ix_club_deployment_id ON t_club (deployment_id)` non-unique btree — Postgres does NOT auto-create FK indexes; `forEachClub` does `SELECT ... WHERE deployment_id = ?` per Deployment per tick. Without it every iteration is a seq scan.
- **No `@OneToMany Deployment.clubs` collection.** Query `t_club` directly by `deployment_id` (projection), let `forEachClub` callback open its own tenant-scoped session per Club. Don't invite lazy-init under `UnscopedTenantContext` or Cartesian risk when callers fetch-join.
- **Iteration is sequential.** 100 Deployments × 5 Clubs ≈ 500 iterations/tick, well under any job's body cost. NO thread pool here — explicit follow-up if a job body crosses ~minutes. Order is deterministic by `deployment.id` so partial-failure resumption is trivial.
- **No caching.** Lifecycle state changes are rare but correctness-critical (a `deleting` Deployment must NOT receive job traffic on the next tick). Re-query each tick; the index makes it free.
- **Tenant-context window cost.** ThreadLocal set/clear ~1 μs. Pin a unit test asserting the ThreadLocal is restored on exception path (finally block) — leakage corrupts the next iteration's tenant.
- **Backfill migration.** One UPDATE + two INSERTs on ~12 rows; sub-100 ms. Single txn; no `statement_timeout` concern.
- **One-shot bench at story-completion.** Time `DeploymentContext.forEachClub` over seeded fixture of 100 Deployments × 5 Clubs with both indexes; pass threshold < 50 ms wall (excludes job body). Slower = FK index not used; check `EXPLAIN`.

<!-- modernize-refine: end -->
