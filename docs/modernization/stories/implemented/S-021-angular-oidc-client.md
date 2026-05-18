---
id: S-021
title: Angular OIDC client (Authorization Code + PKCE)
epic: E-03
status: done
started_at: 2026-05-18
done_at: 2026-05-18
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
refined: true
refined_at: 2026-05-18
refined_specialists: [requirements, solution, qa, security, performance]
context7_last_checked: 2026-05-18
github_issue: 71
github_pr: 72
---

## Context

SPA-side counterpart to S-020. Replaces the legacy "POST /Token + sessionStorage + `$http.defaults`" model with proper OIDC PKCE + silent refresh. Replaces legacy `core/AuthService.js`.

## Acceptance criteria

See frontmatter.

## Cross-story contracts

- **`clubId` claim is SPA-cosmetic, server-authoritative.** `SessionStore.currentClubId` is for UI display + client-side routing only. Every API call's tenant scope is resolved server-side by S-022's `ClubTenantIdentifierResolver` reading the validated JWT. The SPA NEVER sends `clubId` as a request parameter, header, or body field. The mapper accepts `clubId: null` (federated / not-yet-imported users); the server falls back to a DB lookup by `keycloak_sub` / email.
- **CSP** — `connect-src 'self' <keycloak>`, `form-action 'self' <keycloak>`, `frame-ancestors 'none'`, no `unsafe-inline`. Owned by S-041 (reverse proxy).
- **PII redaction in observability** — GlitchTip breadcrumbs scrub the `Authorization` header; user context is `user.id = sub` only (no `email` / `name`). Owned by S-034.
- **SPA auth events are NOT audit-log records** — S-027 audit log is server-side only. Console / GlitchTip breadcrumbs for login / silent-refresh-failed / logout are diagnostic, not forensic.
- **OIDC authority is absolute, exempt from the CLAUDE.md "no hardcoded absolute server URLs" rule.** The library validates `authority` against the `iss` claim Keycloak stamps on tokens; a relative URL trips the issuer-mismatch check on wellknown retrieval. Dev value `http://localhost:8090/realms/alpenflight` is hardcoded with a TODO; S-041 swaps via `environment.ts` file replacement at prod cutover.
- **CSP is split: dev `<meta>` in `index.html` + prod response header.** `angular.json` `production` configuration fileReplaces `src/index.html` → `src/index.prod.html` (no inline CSP). S-041's reverse proxy injects the canonical CSP response header in prod. Dev `connect-src` allows `http://localhost:8090` so the OIDC library can hit Keycloak.
- **Post-login deep-link preservation.** `authGuard` stamps the requested URL into `sessionStorage` before `oidcSecurity.authorize()`; `OidcSessionBridge` consumes the stamp on `NewAuthenticationResult` and `Router.navigateByUrl`s back. `triggerAuthorizationResultEvent: true` suppresses the lib's default `navigateByUrl(postLoginRoute = '/')`. Fallback when no stamp exists: `/clubs` (the default post-auth landing for first-time login from `/`).

## Smoke test outcome

Operator validated the happy path end-to-end against `next/ops/dev-up-full.sh` on 2026-05-18: cold-start login (`/clubs` → Keycloak PKCE → German UI → `clubadmin1` → back to `/clubs`) and RP-initiated logout via the inline header link. Surfaced + fixed three day-zero gaps inline (absolute `authority`, dev-CSP split from prod, deep-link preservation). Detailed validation of the remaining 7 of 9 checklist items (Bearer scoping, silent refresh, multi-tab logout, hard-401 redirect, etc.) is deferred to a follow-up automated harness rather than re-run manually — see follow-up below.

## Follow-up: real-OIDC Playwright harness

Stand up a Playwright project that boots the SPA under `--configuration=development` (real OIDC) with `next/ops/dev-up-full.sh` as a prerequisite. Cover the smoke-test checklist mechanically: login flow including PKCE params + `ui_locales=de`, Bearer attachment scoping (`/api/v1/*` only), silent refresh via Keycloak Admin REST `accessTokenLifespan` shortening, RP-initiated logout against `end_session_endpoint`, multi-tab logout detection (two `browser.newContext()` sharing `storageState`), hard-401 redirect (user-disable via Admin REST), public-route stays-public assertion. Serial-describe the realm-mutating specs to avoid races. CI job needs a Keycloak-up step; one option is to reuse the `dev-up-full.sh` flow.
