# 0007 — Auth scheme

- **Status:** Accepted
- **Date:** 2026-05-14
- **Decision criteria** (from [vision §6](../02-vision-and-constraints.md#6-decision-criteria-for-phase-3)): off-EOL · Swiss/EU residency · structural multi-tenancy supported · solo-operator operability · mature ecosystem

## Context

Current auth is OAuth2 password grant against `/Token` issuing a 14-day bearer token; no refresh, no global 401 handler in the SPA, no proactive revocation, sessionStorage-only on the client ([current-state §7 R10](../01-current-state.md#r10--oauth-bearer-with-no-refresh-no-401-interceptor)). The vision pins:
- **C13** — refresh-token-based auth is non-negotiable.
- **C14** — forced password reset for all users at cutover (passwords are not migrated).
- **NFR** — short-lived access tokens + refresh tokens (or sliding sessions); audit log on every mutating action.

Spring Security 7 (with [ADR 0001](0001-backend-language-and-framework.md) Spring Boot 4) is OIDC-fluent out of the box: as a resource server it validates JWT/opaque tokens against an issuer's JWKS; as a client it can act as the SPA's OIDC partner. The protocol stays the same regardless of which identity provider issues the tokens, which makes the runtime / provider choice a configuration concern rather than a code concern.

The operator chose to run a self-hosted IdP locally during development and a hosted (or separately-deployed) IdP in production — both speaking OIDC. The ADR therefore locks the *protocol* (OIDC + OAuth2) and the *per-environment IdP shape*, but defers the specific production IdP selection until deployment is closer (open item).

## Options considered

### Option A — Self-issued JWT access + opaque refresh tokens via Spring Authorization Server
- **Capabilities:** Spring runs its own authorization server alongside the resource server. No external IdP.
- **Fit to criteria:** solo-operator operability ✓ (one process), Swiss/EU residency ✓, mature ecosystem ✓.
- **Why not chosen:** mixes authz-server responsibilities into the application; password reset, email confirmation, MFA, account lockout, audit, social login all become bespoke code we own. A dedicated IdP gives these for free.

### Option B — Session cookies + DB-backed session store
- **Capabilities:** `HttpOnly Secure SameSite=Strict` cookies, trivial revocation, simple model.
- **Fit to criteria:** operability ✓, residency ✓.
- **Why not chosen:** doesn't fit the OIDC-everywhere shape the operator wants; Proffix sync ([current-state §4](../01-current-state.md#4-integration-map)) currently uses bearer tokens — moving to cookies would force a parallel token mechanism for machine clients.

### Option C — Self-hosted OIDC IdP (Keycloak / Authentik) — chosen for local development
- **Capabilities:** Keycloak (Java/Quarkus, Red Hat-backed) and Authentik (Python/Django) both provide full OIDC, OAuth2 client-credentials, password reset, email confirmation, MFA, social login, account lockout, admin UI for users / clients / realms. Both ship Docker images suitable for `docker-compose`.
- **Fit to criteria:** Swiss/EU residency ✓ (runs on any Linux VPS in CH/EU). Operability ~ (separate service to run; mitigated locally by `docker-compose`). Mature ecosystem ✓ (Keycloak is the de facto OSS IdP).
- **Migration cost:** medium — seed users/realms/clients via Keycloak's bootstrap; map current users on cutover (C14 forces a reset email).
- **Ecosystem risk:** low — both projects are widely used.
- **Escape hatch:** OIDC is standard; swap providers by changing the issuer URL and re-registering clients.
- **Why chosen for local:** the developer can `docker compose up` and get a real OIDC issuer at `localhost:8080/realms/fls` with no cloud dependency, matching production semantically.

### Option D — Hosted OIDC IdP — chosen for production deployment
- **Capabilities:** outsource hosting, scaling, patching, MFA, social login, password reset flows.
- **Fit to criteria:** off-EOL ✓. Operability ✓ (no IdP to operate). Swiss/EU residency ✓ — **only if** the chosen vendor offers an EU/Swiss region and a residency commitment. This is a hard gate.
- **Migration cost:** low at deployment time — same OIDC contract; configure issuer URL + client credentials.
- **Ecosystem risk:** vendor lock-in is real if we lean on proprietary extensions (Auth0 Rules, Clerk components, etc.). Mitigation: stay on the OIDC/OAuth2 standard surface — no vendor-specific SDK on the backend.
- **Escape hatch:** swap to self-hosted Keycloak (i.e. the local-dev configuration) if the hosted vendor disappears, raises prices, or fails on residency. The application code doesn't change.
- **Candidates (deferred):**
  - **Ory Network** — Germany-headquartered, GDPR-first, EU residency built in. Strongest on criterion 4.
  - **Logto Cloud** — EU region available, OSS roots (can self-host as fallback). Younger product.
  - **Auth0 (Okta-owned)** — EU region (Frankfurt). US ownership raises CLOUD Act questions for some Swiss customers; verify before committing.
  - **Self-hosted Keycloak on the production VPS** — operationally the same as the local-dev setup, just deployed. Cheapest, no third-party residency questions. Worth keeping on the table when the deployment ADR ([ADR 0010](.)) is firmed up.

## Decision

Chosen: **OIDC + OAuth2 as the protocol**, with **self-hosted Keycloak (or Authentik) for local development** and **a hosted OIDC IdP for production**, provider selection deferred until deployment is concrete.

Implementation shape:
- Backend is configured as an OAuth2 **resource server** (Spring Security 7). It validates incoming JWT or opaque tokens against the configured issuer's JWKS / introspection endpoint. The issuer URL is the only auth-related config that differs across environments.
- Token shape: short-lived (≈15 min) access tokens + rotating refresh tokens (≈30 days idle expiry, ≈90 days absolute). Specific values pinned in the IdP's realm/tenant config, not in application code.
- Frontend is an OIDC public client (Authorization Code + PKCE flow). Uses an Angular OIDC library (`angular-auth-oidc-client` or equivalent — phase-4 story).
- Proffix sync uses the OAuth2 **client-credentials** grant against the same IdP; a dedicated machine client with read-only scopes for `/api/v1/deliveries/*`.
- Cutover (C14): a script extracts the user → email list from the old SQL Server DB, creates corresponding users in the new IdP marked "must reset password," and the IdP emails the reset link.

## Consequences

- **Positive:**
  - Application code is IdP-agnostic — local vs. production differs only in `spring.security.oauth2.resourceserver.jwt.issuer-uri` and the frontend's `issuer` config.
  - Password reset, email confirmation, MFA, account lockout, audit log of auth events all live in the IdP and don't need bespoke application code (closes a large slice of the old `IdentityUserManager` complexity).
  - Standard OIDC surface keeps Proffix machine-client integration straightforward.
  - Refresh-token rotation gives [R10](../01-current-state.md#r10--oauth-bearer-with-no-refresh-no-401-interceptor) a structural fix.
  - 401 handling in the SPA becomes a normal OIDC library concern (silent refresh; redirect to login on hard failure).

- **Negative:**
  - Local dev requires `docker compose up` for the IdP — one more container, one more port, one more set of seed config. Mitigation: bake a Keycloak realm export into the repo so dev setup is one command.
  - Production IdP cost (if hosted) is a recurring expense; self-hosting Keycloak in prod is the cost-free alternative if operability allows.
  - Realm / client / scope configuration becomes a new artifact to version-control. Mitigation: commit Keycloak realm JSON exports under `next/auth/`.
  - Swiss/EU residency must be re-verified at production-IdP selection time; the residency promise from C4 is now partly a vendor-due-diligence task.

- **Follow-ups (other ADRs / stories implied):**
  - **Open item:** select the production IdP vendor (or commit to self-hosted Keycloak in prod). Decide before deployment ([ADR 0010](.)). Criteria: Swiss/EU residency proof, OIDC-standard surface only, MFA support, pricing fits the operator's expected MAU.
  - **Story:** stand up Keycloak (or Authentik) in `docker-compose.yml`; seed realm + initial admin client + SPA client + Proffix machine client; commit the realm JSON export.
  - **Story:** wire Spring Security 7 as OAuth2 resource server; validate the local Keycloak token in a smoke endpoint.
  - **Story:** wire the Angular OIDC library into the SPA; verify Authorization Code + PKCE end-to-end against local Keycloak.
  - **Story:** write the cutover user-export-and-import script (old SQL Server DB → IdP users with reset-password flag set).
  - **Story:** define authorization model — roles vs. scopes vs. permissions in IdP and how Spring Security maps them to method security (`@PreAuthorize`). Implementation detail of [ADR 0008](.) (multi-tenancy).
  - **Story:** audit-log every mutating endpoint (overlaps with the audit-log story; the IdP gives auth-event auditing for free, but business-action audit is the application's job).
  - **Story:** ensure the OpenAPI spec ([ADR 0005](0005-api-shape.md)) carries the right security schemes so the generated TS client and Swagger UI handle OIDC correctly.
