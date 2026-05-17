---
id: S-138
title: Trial-Deployment provisioning on first successful ingest
epic: E-15
status: todo
depends_on: [S-134, S-137, S-141]
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
