---
id: S-142
title: Trial countdown UX + 72 h hard-delete cron
epic: E-15
status: todo
depends_on: [S-137, S-138, S-141, S-081]
acceptance:
  - When a user is signed into a Club whose parent Deployment has `lifecycle_state = trial`, every SPA page renders a persistent countdown banner: "Your trial expires in {hh:mm} — your data will be deleted unless you subscribe." CTA → `/account/subscribe` (S-145).
  - Backend exposes `GET /api/v1/deployments/myDeployment/trial-status` returning `{ trial_started_at, expires_at, hours_remaining, state, club_count }`. SPA polls or computes client-side.
  - Scheduled job `TrialExpiryJob` runs every 10 minutes (per vision §2 NFR ±15 min precision; tagged `@LifecycleStateFilter({ TRIAL })`). For every Deployment in state `trial` with `now > trial_started_at + 72h`:
    - Audit-log: `deployment.trial_expired_deleted` with the Deployment snapshot summary (club_count, row_count_total).
    - Transition state to `deleting`.
    - Cascade hard-delete: every tenant-scoped row in every Club of the Deployment, every Club row, the Deployment row itself. Use the S-011 catalog + `DeploymentContext` (S-137) to iterate.
    - Deprovision the Keycloak group (`deployment-{id}`) and the admin user's membership (refine: full user delete vs. group-only — operator's call).
    - Idempotent: re-running on an already-deleted Deployment is a no-op.
  - GDPR data-subject delete: an authenticated user calling `DELETE /api/v1/deployments/myDeployment` from a `trial` Deployment short-circuits the countdown and runs the same delete cascade immediately.
  - Funnel-telemetry: `trial.deleted_auto`, `trial.deleted_user_requested`.
  - Unit test asserts the delete job's queries cannot touch any non-`trial`/non-`deleting` Deployment's rows.
estimate: M
adr_refs: [0018]
parity_test: tests/migration/trial-expiry.spec.ts (new)
---

## Context
Vision C29: 72 h ephemerality is non-negotiable. The countdown is the conversion-pressure mechanism; the cascade-delete is the trust signal.

A trial Deployment promoted to `active` mid-trial (S-146) is no longer enumerated by this job — the state filter does the work. No "cancel the scheduled delete" mechanism needed; state transition is the cancel.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] `TrialExpiryJob` via Spring `@Scheduled` + `UnscopedTenantContext` (S-023) + `@LifecycleStateFilter({ TRIAL })` (S-137).
- [ ] Trial-status endpoint.
- [ ] SPA countdown banner component + interceptor that injects trial-status context.
- [ ] Deployment cascade-delete service using S-011's catalog + `DeploymentContext`.
- [ ] Keycloak admin-client integration: remove user / group on Deployment delete.
- [ ] Audit-log integration for the delete event (audit rows survive — they're not tenant-scoped; refine).
- [ ] GDPR data-subject delete handler.

## Notes
- 72 h is from `Deployment.trial_started_at` (set at S-138's provisioning moment), not from signup. A user who signs up but never uploads is not on a clock.
- Audit-log rows persist after Deployment deletion; they reference a `deployment_id` / `club_id` that no longer has a corresponding row. UIs that try to render the name show a placeholder.
- Deletion cascade is large (up to 2 GB of data per the §2 NFR ceiling). Refine: chunked deletes if a single transaction can't fit.
