---
id: S-028
title: Bulk-provision tenant users in Keycloak (admin endpoint)
epic: E-03
status: todo
depends_on: [S-019, S-026, S-052, S-082, S-141]
acceptance:
  - `POST /api/v1/admin/clubs/{clubId}/users/bulk-provision` (system-admin role only) walks the new Postgres `user` table for the target tenant and, for each row without a `keycloak_sub`:
    - Creates a matching Keycloak user in the production realm with required action = `UPDATE_PASSWORD`.
    - Maps legacy roles → Keycloak realm roles per S-026.
    - Sets `user.keycloak_sub` on the Postgres row.
    - Sends a Keycloak reset-password email via S-082's JavaMailSender (the `sendEmails` flag below controls whether emails actually go out vs. dry-run).
  - Request body: `{ dryRun: boolean, sendEmails: boolean }`. Dry-run returns the per-user plan (email, club, roles, action `create` / `skip — already exists`) without writes. `sendEmails=false` is supported for staged rollouts where the operator wants Keycloak users created before the emails are sent.
  - Idempotent: re-invocation produces no duplicate Keycloak users (`searchByEmail` first); users that already have `keycloak_sub` are skipped.
  - Admin UI: on the tenant-admin page, a "Provision all users" button calls the endpoint after the operator confirms a dry-run preview.
  - Audit-log: emits a `tenant.users_bulk_provisioned` event with counts (created / skipped / failed).
  - **Operator-onboarded tenants only.** Public self-service ingests do NOT auto-fire this endpoint (deliberate — see notes).
estimate: M
adr_refs: [0007, 0018]
parity_test: tests/onboarding/bulk-provision.spec.ts (new)
---

## Context
C14: legacy passwords never migrate. Legacy `User` rows arrive in Postgres via the bundle ingest (S-141); Keycloak is on a separate axis, so each user needs a fresh Keycloak account with a reset-required action.

For the operator's own clubs (or any tenant the operator administers directly), this fires once per tenant — the operator hits the button (or curls the endpoint) after flipping `lifecycle_state` to `active`. The `sendEmails` flag lets the operator stage the email blast separately from the user-creation step.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] `BulkUserProvisioningService` in the `tenancy` / `admin` module.
- [ ] Admin endpoint scaffold + system-admin authorization (per S-026).
- [ ] Keycloak admin-client wiring (shared with S-138's per-signup provisioning).
- [ ] Admin-UI button + dry-run preview component on the tenant-admin page.
- [ ] Funnel-telemetry: `onboarding.users_bulk_provisioned` (per S-147).

## Notes
- Public self-service customers' bundles also carry legacy `User` rows. Those are intentionally left as Postgres records without Keycloak identities until a customer-side "invite my pilots" feature (future story) turns them into accounts one-by-one. Avoids blasting reset emails at strangers and creating accounts that may never be used.
- Reset emails go out via S-082's JavaMailSender using Keycloak's built-in reset-password email template. Volume management (avoiding SMTP rate-limits) is handled by S-091's prod relay configuration.
