---
id: S-021
title: Angular OIDC client (Authorization Code + PKCE)
epic: E-03
status: todo
depends_on: [S-002, S-006, S-019]
acceptance:
  - `angular-auth-oidc-client` (or equivalent) is wired against Keycloak.
  - A "log in" button initiates the Authorization Code + PKCE flow; user authenticates against Keycloak; tokens land on the SPA.
  - The generated TS client (S-004) is configured to attach the access token to every `/api/v1/*` request via an HttpInterceptor.
  - Silent refresh works: access tokens expire after 15 min; user stays signed in via refresh-token rotation (configured in Keycloak).
  - The `SessionStore` from S-006 is populated from the OIDC user info + token claims (`clubId`, roles).
  - Hard 401 (refresh failed) redirects to login.
estimate: L
adr_refs: [0007, 0006]
parity_test: none
---

## Context
SPA-side counterpart to S-020. Replaces the legacy "POST /Token + sessionStorage + `$http.defaults`" model with proper OIDC PKCE + silent refresh.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Install `angular-auth-oidc-client`; configure against `localhost:8080/realms/fls`.
- [ ] Wire `LoginCallback` route.
- [ ] HttpInterceptor that attaches the access token to outgoing requests to `/api/v1/**`.
- [ ] Silent refresh configuration (the library handles the iframe + refresh-token rotation).
- [ ] Wire `SessionStore` (from S-006) to read from the OIDC service's signals/observables.
- [ ] Route guard that redirects unauthenticated users to login.
- [ ] Logout flow: clear tokens, post-logout redirect URL.

## Notes
This story is L because OIDC + refresh + SPA interceptors + route guards + store wiring is genuinely a lot. Tasks split it. Test with both happy path (login → fetch → silent refresh → fetch) and failure paths (expired refresh → redirect to login).

Replaces legacy `core/AuthService.js`.
