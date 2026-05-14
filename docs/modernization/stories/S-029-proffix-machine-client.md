---
id: S-029
title: Proffix machine client (client-credentials grant)
epic: E-03
status: todo
depends_on: [S-019, S-020]
acceptance:
  - The Keycloak realm export (S-019) includes the `fls-proffix` client with `client_credentials` grant enabled, a dedicated scope (`deliveries:read`), and a client secret managed via env.
  - The Spring Security config maps `client_credentials` callers to a synthetic principal whose authority is `ROLE_PROFFIX_SYNC`.
  - The `/api/v1/deliveries/*` GET endpoints can be reached with a `client_credentials`-issued token; nothing else can.
  - Audit log captures these accesses with `actor = "proffix-sync"` and the tenant context (the Proffix client passes `clubSlug` per request, similar to S-025, since it pulls per-club).
estimate: M
adr_refs: [0007]
parity_test: none
---

## Context
ADR 0007 calls this out. The PROFFIX-FLS-Sync repo pulls from `/api/v1/deliveries/*` over OAuth2 bearer today; same shape continues, just against Keycloak-issued tokens.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Add the machine client to the realm export.
- [ ] Spring Security mapping for client-credentials principal.
- [ ] Restrict to the deliveries GET endpoints via `@PreAuthorize` + a `proffix-sync` role check.
- [ ] Document the Proffix-side integration steps: client ID, secret, scope, target URL.
- [ ] Smoke test: simulate `PROFFIX-FLS-Sync`'s call pattern against the new endpoint with a real token.

## Notes
S-080 verifies the actual `/api/v1/deliveries/*` payload shape matches what Proffix consumes. This story is just the auth surface.
