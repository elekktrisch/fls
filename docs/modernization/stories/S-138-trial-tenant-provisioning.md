---
id: S-138
title: Trial-Deployment provisioning on first successful ingest
epic: E-15
status: in_progress
started_at: 2026-05-28
github_issue: 162
depends_on: [S-134, S-137]
integration_base: integration/migration
acceptance:
  - On the first `POST /api/v1/migrations/{uploadId}/ingest` call that completes successfully (S-141), a new Deployment row is provisioned for the signed-in Keycloak user: `lifecycle_state = trial`, name derived from the bundle's manifest (legacy FLS instance display name), `plan = free`, `trial_started_at = now`. The audit-actor is the Keycloak user.
  - One Club row is provisioned per Club in the bundle, each with `deployment_id` set to the new Deployment, and all the Club-scoped data from the bundle is hung off these Clubs.
  - A Keycloak group named `deployment-{deploymentId}` is created (or reused); the Keycloak user is added to it. Per-Club roles inside the Deployment are also created (refine: `deployment-{id}-club-{clubId}-admin` naming).
  - Seed reference data not present in the bundle is bootstrapped per Club: countries (S-047 walking-skeleton slice), default flight-types, default cost-balance type — refine which catalog is "always present" vs. "ported from bundle".
  - The user's session token is refreshed so the new Deployment + initial-Club claim is in-band; the SPA routes to `/dashboard` with the tenant context resolved to the user's first Club.
  - Funnel-telemetry event `deployment.provisioned` fires (S-147) with `club_count`.
  - If the user already owns a `trial` or `active` Deployment, second-ingest rejects with structured 409 pointing at the existing Deployment.
estimate: M
adr_refs: [0007, 0008, 0018]
parity_test: tests/migration/trial-provisioning.spec.ts (new)
refined: true
refined_at: 2026-05-28
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer]
---

## Context
Vision C31 + C34: tenant creation is deferred from signup to first-successful-ingest, and the new entity provisioned is a *Deployment* (with 1..N Clubs hung off it from the bundle), not a single Club. The 72 h clock starts at ingest, not at signup. One user, one Deployment.

This story owns the provisioning logic — the *trigger* is inside S-141, which calls into this story's service at the right moment.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] `DeploymentProvisioningService` in the `tenancy` module.
- [ ] Keycloak admin-client wiring to create the Deployment group + assign the user.
- [ ] Per-Club reference-data bootstrap (factor with S-047).
- [ ] SPA: re-fetch identity / token after provisioning so tenant context resolves immediately.
- [ ] Reject second-ingest with structured 409.

## Notes
- Keycloak admin client uses a service-account token (the same machine-client S-029 plumbing, different audience — refine in ADR 0018).
- Operator-administered Deployments (operator's own clubs) go through the same service but get manually flipped to `active` via S-028 / S-137's admin endpoint post-provisioning, bypassing the trial countdown.
- A Deployment may contain multiple Clubs even on free tier *at provisioning time*. The C30 "1 Club on free" cap is enforced as a **create-time** check on subsequent Club creation, not retroactively. Bundles that contain more than 1 Club are still ingested in full; the user sees an upgrade prompt to keep them all.

<!-- modernize-refine: start -->

## Design notes

`DeploymentProvisioningService` runs inside S-141's per-Club ingest txn boundary + post-commit KC reconcile loop. The DB-half is transactional with the bundle ingest; the KC-half is best-effort + retried by S-141's hourly `kc_pending` job. S-138 owns no public endpoint — provisioning is migration-internal.

- **Idempotency key = `(migration_run.id)`.** S-141's parent `migration_run` row carries the (user, upload) correlation. Second call with the same `migration_run.id` short-circuits to "load + return existing Deployment + clubIds." The AC-7 409 fires on a *different* `migration_run` from a user who already owns a `{trial, active, past_due, cancelled}` Deployment — operator-grilled 2026-05-28; `deleting` and `sandbox` are exempt (data is gone or shared-fixed).
- **`Deployment.owner_keycloak_sub` + partial UNIQUE land in S-137** (amended this PR). The 409 gate is structural: `INSERT INTO t_deployment (owner_keycloak_sub, …)` raises `unique_violation` when the user already has a non-terminal Deployment; service catches + returns the structured 409.
- **Two-phase ordering matches S-141's pin.** Phase A inside the per-Club txn: `Deployment.startTrial()` → Deployment row → Club rows with `deployment_id` FK → reference-data seed → commit. Phase B post-commit, best-effort: KC group create-or-reuse, user-add, per-Club realm role create-and-assign. KC failure flips `migration_run.kc_state = pending`; the S-141 retry job re-invokes Phase B via the same `migration_run.id`. DB state is the source of truth; KC is reconciled toward it.
- **KC topology: one group per Deployment + per-Club realm roles.** Group `deployment-{deploymentId}` carries membership; per-Club admin authority is granted via realm roles `deployment-{deploymentId}-club-{clubId}-admin` (NOT group attributes — keeps `@PreAuthorize("hasRole(…)")` working per S-026). Verbose role naming is acceptable; the IDs are UUIDs the operator never types.
- **JWT `clubId` claim = manifest-declared primary Club.** Bundle manifest carries an explicit `primaryClubId` field (S-141/S-183 surface it). Fallback when absent: the Club with the lowest `legacy_int_id` (deterministic, matches the legacy single-tenant assumption). The KC user-attribute `clubId` is written via the same admin-REST machine client.
- **Reference-data bootstrap = TENANT_SCOPED defaults only.** SYSTEM_GLOBAL refs (Country / Language / ClubState / Role / StartType / unit types) are already seeded by V2 Flyway baseline with canonical UUID v7 + the `legacy_int_id` hook (per S-016 / S-183 contract). S-138 only seeds per-Club TENANT_SCOPED defaults: `FlightType` (training, glider-tow, private, ferry), `CostBalanceType` (flight-hour, landing, tow), `MemberState` (active, passive, junior). Insert path: `INSERT … ON CONFLICT (club_id, code) DO NOTHING` — bundle wins per S-141's grilled decision.
- **SPA refresh signal = response payload, not SSE.** S-141's `/api/v1/migrations/{uploadId}/bundle` 200 response carries `{deploymentId, clubIds, primaryClubId, kc_pending: boolean}`. SPA calls `OidcSecurityService.forceRefreshSession()` (the S-021 wrapper); on `kc_pending=true` the SPA shows "finishing setup…" + polls `/me` every 2 s until the `clubId` claim appears. No SSE — S-176 lives on `integration/users-suite`; cross-branch coupling avoided.
- **Operator-bypass is the same path + a manual flip.** Self-service ingest always lands `lifecycle_state=trial`. Operator-owned tenants go through the same `DeploymentProvisioningService` then call S-137's admin endpoint to flip to `active`. No branching inside the service.
- **Audit actor = the Keycloak user, not the service account.** The KC user from the request principal is the audit actor for Phase A + B alike. Phase B's machine-client identity is operational metadata, not the audit subject. S-027's `ActorResolver` flushes the Deployment + Club rows before resolving — verify the audit listener doesn't pre-resolve before flush in the same txn.
- **Funnel telemetry placeholder.** `INFO funnel event=deployment.provisioned deploymentId=… clubCount=… userId=… correlationId=…` JSON-shaped log line; swap to S-147's `FunnelTelemetry.emit` when that helper lands.
- **Schema deviation from ADR 0022 D2: none.** Lifecycle transitions live on the `Deployment` aggregate (S-137); the new partial UNIQUE is structural identity uniqueness, not business logic.

## Edge cases & hidden requirements

- **Manifest-declared Deployment name carries no uniqueness constraint.** Two operators may ingest bundles with identical instance display names; that's expected.
- **`trial_started_at` precision = `Instant.now(clock)` at the provisioning commit moment.** Not handshake / upload-start / decrypt-complete. S-142's 72 h cron computes against this stamp.
- **Empty-bundle Club path.** A bundle declaring a Club with zero TENANT_SCOPED ref rows still receives the defaults; bootstrap fires per-Club unconditionally + ON CONFLICT no-ops if the bundle already inserted.
- **`bundle.json.deployment_id` is server-stamped, never bundle-supplied.** Per security plan: the mapper for `Club` ignores any inbound `deployment_id` field. Defends against a malicious bundle smuggling a Club into another user's Deployment.
- **Free-tier Club-count is NOT enforced at provisioning.** Per vision C30, the cap is a *create-time* check on subsequent Club creation (S-143 owns). Bundles with N Clubs land as N Clubs regardless of plan.
- **409 body shape:** `{ code: "DEPLOYMENT_EXISTS", deployment_id, deployment_name, lifecycle_state, club_ids: [...] }` — SPA needs this to render the "go to your existing tenant" CTA.
- **KC role-vs-group reconcile on Phase-B retry.** Per role: "create if absent + assign if not assigned." Per group: "create if absent + add user if not member." No transactional boundary across KC + Postgres — retry must be repeatable without double-add.
- **KC group + role lifecycle on Deployment delete.** S-142 owns the trial-delete cascade; hand-off: S-142 must DELETE `deployment-{id}` KC group + all `deployment-{id}-club-{clubId}-admin` realm roles inside its txn.

## Security plan

- **Authz anchor: `migration_upload.created_by_user_id == principal.sub`** re-asserted at `DeploymentProvisioningService.provision(uploadId, principal)` inside the ingest txn. No standalone public endpoint exists. Cross-user `uploadId` hijack returns 404 (don't leak existence).
- **Null-tenant write context: `MigrationIngestTenantContext`** (S-141's pin). ArchUnit whitelist is an explicit FQN allowlist: `migration.ingest.*` + `tenancy.provisioning.DeploymentProvisioningService` only. NO package wildcard. ArchUnit rule fails CI if any other class in `tenancy.*` writes without a `@TenantId` set.
- **KC admin-client scope creep guard.** Provisioning needs `manage-groups` + `manage-realm` ON TOP of S-052's `manage-users / view-users / query-users`. Realm export updates land in this PR's diff; `realm-shape-check` script (S-052) fails CI if the scope set drifts further.
- **One-Deployment-per-user is structural** (partial UNIQUE in S-137 amendment). The 409 path catches `unique_violation` from JPA and translates to the structured body above. No Keycloak round-trip on every ingest attempt — the gate is in Postgres.
- **Audit-event payload PII discipline.** `deployment.provisioned`: actor `{sub, email}` + target `{deploymentId, clubIds, plan, lifecycle_state, club_count}` + after `{deploymentId, lifecycle_state, plan, trial_started_at, club_count}`. NO manifest display name (operator free-text — may contain person names), NO per-Club names, NO bundle Person/User PII. Per-Club creation is bundled into the single event (batch-summary precedent from S-141), not one row per Club.
- **Logging redaction.** `deployment.name` is stored on the Deployment row but never logged or echoed into telemetry. Log lines carry `deploymentId` + `club_count` + `plan` only.
- **Cross-tenant leakage CI extension (S-024).** Add: provision as user A, attempt second ingest as user B targeting A's `uploadId` → 403 before any DB write. Plus the existing per-`@TenantId` checks.
- **OWASP deltas.** A01 — owner-binding is the only authz check; structural-FK + service guard. A04 — schema-level uniqueness closes the race; the application can't bypass. A09 — `deployment.provisioned` doubles as a security signal; burst alerts deferred to S-041 monitoring runbook.

## Test plan

- **Pyramid.** ~12 unit (provisioning policy + KC naming + manifest mapping + reference-merge + 409 decision + audit-payload shape); ~8 IT (Testcontainers Postgres + WireMocked KC admin); 2 e2e (Playwright happy path + duplicate-ingest 409).
- **ITs worth pinning.**
  - Happy N-Club bundle → 1 t_deployment + N t_club + KC group + N admin roles + audit row.
  - Reference-data collision: bundle's customized FlightTypes win; defaults absent for those codes; other defaults present.
  - KC failure compensation: WireMock returns 5xx mid-Phase-B → DB committed, `migration_run.kc_state=pending`, no KC group, audit marked partial; retry hook completes cleanly.
  - Concurrent ingest race: two parallel calls with same `migration_run.id` aligned via `CyclicBarrier` (NOT `Thread.sleep`); one provisions, second returns same `deploymentId`; single KC create attempt; no `unique_violation` surface.
  - Second-ingest 409: actor with `{trial, active, past_due, cancelled}` Deployment → structured 409 body referencing the existing `deploymentId`; `deleting` and `sandbox` exempt (both tested).
  - Operator-flip: provision → S-137 admin endpoint flip to `active` → mock-clock past 72 h → trial-expiry sweeper does NOT touch this Deployment.
  - Cross-tenant leakage: pre-seed Deployment A; provision Deployment B under null-tenant; B queries return only B's clubs.
  - Audit completeness: exactly one `deployment.provisioned` row per success; payload carries before=null + after=full snapshot.
- **E2E (Playwright against compose KC + Mailpit).** Signup → handshake → upload tiny 2-Club bundle → ingest → response carries `{deploymentId, clubIds, primaryClubId}` → SPA force-refreshes OIDC session → `/me` carries new `clubId` claim → lands on `/dashboard` scoped to primary Club. Duplicate-ingest path surfaces the 409 with link to existing Deployment.
- **Fixtures.** Two committed bundle JSON resources under `tenancy/src/test/resources/bundles/`: 1-Club minimal + 3-Club with custom FlightType labels. KC admin WireMock stub-set: success / mid-flight 503 / idempotent re-create returning existing group. Mock `Clock` bean for trial-expiry test.
- **Parity strategy.** N/A — greenfield SaaS shape; no legacy Deployment concept.
- **Risks.** (1) Concurrent-race IT flake — `CyclicBarrier`, assert on response 409, not timing. (2) WireMock KC drift from real KC admin REST — pin one IT against compose KC (reuse S-052 harness) as a contract check. (3) Token-refresh e2e is timing-sensitive — assert on the `/me` claim change, not on a UI redirect timeout.

## Performance plan

(N/A — provisioning is one-time per ingest at operator scale; no hot path, no streaming, no N+1. KC admin calls in Phase B are 4 sequential round-trips per Club worst case (group-create or reuse, user-add, role-create, role-assign) — acceptable inside the 15-min ingest budget. Batching deferred to S-141's retry job design.)

<!-- modernize-refine: end -->
