---
id: S-138
title: Trial-Deployment provisioning on first successful ingest
epic: E-15
status: done
started_at: 2026-05-28
done_at: 2026-05-28
merged: true
merged_at: 2026-05-28
github_issue: 162
github_pr: 163
depends_on: [S-134, S-137]
integration_base: integration/migration
acceptance:
  - On the first `POST /api/v1/migrations/{uploadId}/ingest` call that completes successfully (S-141), a new Deployment row is provisioned for the signed-in Keycloak user: `lifecycle_state = trial`, name derived from the bundle's manifest (legacy FLS instance display name), `plan = free`, `trial_started_at = now`. The audit-actor is the Keycloak user.
  - One Club row is provisioned per Club in the bundle, each with `deployment_id` set to the new Deployment, and all the Club-scoped data from the bundle is hung off these Clubs.
  - A Keycloak group named `deployment-{deploymentId}` is created (or reused); the Keycloak user is added to it. Per-Club roles inside the Deployment are also created (`deployment-{id}-club-{clubId}-admin` naming).
  - Seed reference data not present in the bundle is bootstrapped per Club: default flight-types + default member-states. Cost-balance types stay system-global.
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

Vision C31 + C34: tenant creation is deferred from signup to first-successful-ingest, and the entity provisioned is a *Deployment* (with 1..N Clubs hung off it from the bundle), not a single Club. The 72 h trial clock starts at ingest, not at signup. One user, one non-terminal Deployment (enforced structurally by `ux_deployment_owner_active`).

## Cross-story contracts

- **S-141 (bundle ingest)** is the only production caller: invokes `DeploymentProvisioningService.provision` inside the per-Club ingest transaction, then `reconcileKeycloak` post-commit (Phase B is `REQUIRES_NEW`, so calling it inside an open caller transaction would commit out of order). S-141's hourly reconcile job re-invokes `reconcileKeycloak` for every `kc_state = PENDING` Deployment until it succeeds.
- **S-142 (trial-delete cascade)** must DELETE the Keycloak group `deployment-{id}` and all `deployment-{id}-club-{cid}-admin` realm roles inside its txn. Label conventions are centralised in `KeycloakDeploymentNames`.
- **S-147 (funnel telemetry)** replaces the placeholder `LOG.info funnel event=deployment.provisioned` line with the real emitter when it lands. The field set (`deploymentId`, `clubCount`, `plan`) is the security-plan minimum — additions must keep PII off the wire.
- **Reference data**: V3 baseline seeds the system-global `t_flight_cost_balance_type` catalogue; the per-Club seeder only owns `t_member_state` + `t_flight_type` defaults. The original refinement listed cost-balance as per-Club; the implementation diverges deliberately because the entity is system-global.

## Open question for the operator (carried forward)

**Primary-Club resolution diverges between `provision` and `reconcileKeycloak`.** `provision` honours the manifest-declared `primaryClubId` (or falls back to lowest UUID); `reconcileKeycloak` always picks the lowest UUID for the Keycloak user-attribute. Today the two paths agree because the manifest hint isn't surfaced onto the Deployment row — but the day S-141 plumbs it through, the reconcile retry would write a different `clubId` claim than the synchronous response promised. Fix is one of (a) persist `primaryClubId` on Deployment, (b) re-derive in both paths via a single helper that takes the same inputs. Deferred until S-141 surfaces the manifest hint.

## Schema additions

- **V15** — `t_deployment.idempotency_key UUID` + UNIQUE index, `t_deployment.kc_state VARCHAR(16) NOT NULL DEFAULT 'PENDING'`; partial UNIQUE `ux_member_state_club_name (club_id, name) WHERE deleted_on IS NULL` enabling the seeder's bundle-wins ON CONFLICT.

## Realm-export delta

- `alpenflight-backend-admin` service-account gains `manage-groups`. `manage-realm` / `manage-users` / `query-users` / `view-users` were already granted; `check-realm-shape.sh` pin updated.

## Parity strategy

N/A — greenfield SaaS shape. No legacy Deployment / trial-tenant / signup-provisioning concept to compare against.
