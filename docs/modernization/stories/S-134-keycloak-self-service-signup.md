---
id: S-134
title: Keycloak self-service signup + Google IdP federation
epic: E-15
status: todo
depends_on: [S-019, S-021]
acceptance:
  - The Keycloak realm export (S-019) is amended to enable self-service registration on the `alpenflight` realm: the login page surfaces a "Sign up" link; the registration form requires email + password (+ first/last name as Keycloak built-ins).
  - Google is wired as a federated IdP: the login page shows a "Continue with Google" button; OIDC code flow round-trips to Google and back; first-time Google logins are auto-registered as Keycloak users (no manual approval).
  - Email verification is required: new users (both flavors) receive a verification email; unverified users cannot proceed past the post-signup landing.
  - Post-signup landing routes by `intent` query param: `migrate` → `/migrate/start` (the JAR-download flow, see S-139–S-141); `demo` → `/demo`; default → `/migrate/start`.
  - A funnel-telemetry event `signup.completed` fires with `idp ∈ { local, google }` and `intent` (see S-147).
  - No tenant is created at signup. Tenant creation happens at first-successful-ingest (S-138).
estimate: M
adr_refs: [0007, 0018]
parity_test: tests/public/signup.spec.ts (new)
---

## Context
Vision C26 mandates self-service signup with Google IdP federation. The existing S-019 (Keycloak realm) and S-021 (Angular OIDC client) cover login but not signup. This story extends both: realm config enables registration + Google IdP; SPA shows the signup affordances and handles the post-signup `intent` routing.

Tenant provisioning is deliberately NOT in this story (see C25 lifecycle: signup → no tenant yet → `trial` on first ingest). This keeps signup cheap and avoids accumulating zombie tenants from people who sign up and bounce.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Update Keycloak realm export: `registrationAllowed=true`, `verifyEmail=true`, Google IdP config (client_id placeholder; real secret in env).
- [ ] Add Angular routes: `/signup`, `/migrate/start`, `/demo` (the last delegated to S-136 for behavior).
- [ ] Post-signup `intent` cookie / query-param propagation.
- [ ] Document Google IdP setup in the operator runbook (where to register the OAuth client; redirect URIs).
- [ ] Funnel-telemetry hookup.

## Notes
- Google client_id / client_secret live in env, never committed. Realm export references them via Keycloak's env-var substitution syntax.
- Anonymous demo sessions (S-136) do NOT touch Keycloak — they use a server-issued signed cookie, not an OIDC token. Keep the two surface areas clearly separate in code.
