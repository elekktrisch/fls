---
id: E-03
title: Identity, auth & multi-tenancy
status: todo
adr_refs: [0007, 0008]
---

## Goal
Replace the OAuth2 password grant + 14-day bearer token + convention-based tenant filter with OIDC (Keycloak local; hosted/self-hosted prod TBD) + Hibernate `@TenantId` discriminator + a comprehensive audit log on every mutating endpoint. The end state is: a forgotten WHERE-clause-on-`club_id` is structurally impossible from JPA, and a refresh-token rotation keeps users signed in transparently.

## Scope
- In: Keycloak in compose with a committed realm export; Spring Security 7 resource-server wiring; Angular OIDC SPA client; `@TenantId` on every tenant-scoped entity; unscoped-tenant context for cross-tenant ops; cross-tenant leakage test in CI; tenant-from-URL pattern for public flows; authorization mapping (roles → `@PreAuthorize`); audit log infrastructure; bulk-provision-users-in-Keycloak admin endpoint (S-028); Proffix machine-client setup.
- Out: per-feature `@PreAuthorize` annotations (live in feature epics); SPA login UI polish (handled inside Angular OIDC library's defaults); production Keycloak deployment (lives in E-05 / S-151).

## Stories
- [ ] S-019 — Keycloak in docker-compose + realm export committed
- [ ] S-020 — Spring Security 7 OAuth2 resource server wired
- [ ] S-021 — Angular OIDC client (Authorization Code + PKCE)
- [ ] S-022 — `ClubTenantIdentifierResolver` + `@TenantId` plumbing on first entity
- [ ] S-023 — `UnscopedTenantContext` mechanism (system admin, jobs, OGN)
- [ ] S-024 — Cross-tenant leakage CI test (property-based per repository)
- [ ] S-025 — Tenant-from-URL mechanism for public flows
- [ ] S-026 — Authorization model (roles → `@PreAuthorize` mapping)
- [ ] S-027 — Audit-log infrastructure (every mutating endpoint emits an audit event)
- [ ] S-028 — Bulk-provision tenant users in Keycloak (admin endpoint)
- [ ] S-029 — Proffix machine client (client-credentials grant)

## Done when
- Logging in via Angular hits Keycloak (PKCE), exchanges code for tokens, attaches a Bearer JWT to `/api/v1/*`, and the backend validates it against Keycloak's JWKS.
- A JPA query for any tenant-scoped entity, executed without setting tenant context, throws or returns empty by Hibernate filter — verified by a CI test that runs against every repository.
- An audit row is produced for every successful mutating endpoint call (controller-level interceptor or Spring AOP), with actor + tenant + before/after JSON.
- Refresh-token rotation works: 15-min access token expires, frontend silently refreshes, user never sees a login redirect during a session.
