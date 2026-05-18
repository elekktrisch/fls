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
- **`authority: '/realms/alpenflight'` is relative.** Same-origin assumption is load-bearing for the dev proxy + the S-041 prod reverse proxy. Issuer matching against the `iss` claim happens through the OIDC library; verify under the S-041 cutover that the absolute issuer (e.g. `https://auth.alpenflight.ch/realms/alpenflight`) round-trips correctly.

## Manual smoke test (must pass before merge)

The operator runs this checklist by hand against a `next/ops/dev-up-full.sh` bring-up before /modernize-finalize squash-merges. Asserts what the automated suite can't easily catch — the actual Keycloak round-trip, the visible nav state, the redirect URLs.

**Bring-up:** `bash next/ops/dev-up-full.sh`. Wait for the spinner. Confirm: Keycloak on `http://localhost:8090`, backend on `:8080`, Postgres on `:5432`.

1. **Cold-start login** — incognito to `http://localhost:4200/clubs`. Expect: redirect to Keycloak (`/realms/alpenflight/protocol/openid-connect/auth?response_type=code...&code_challenge=...`), login UI renders in **German** (`ui_locales=de` handoff). Log in as `clubadmin1` / `clubadmin1`. Expect: redirect back to `/clubs` with the list rendered; nav shows the user.
2. **Authenticated API call** — DevTools Network: click a club. The `GET /api/v1/clubs/{id}` request carries `Authorization: Bearer eyJ…`.
3. **Bearer NOT attached to non-API URLs** — static assets, the Keycloak callback POST, third-party requests must **not** carry `Authorization`.
4. **Silent refresh visible** — Keycloak admin → `alpenflight` realm → Tokens → shorten `Access Token Lifespan` to 60s. Reload the SPA, wait 70 s idle. DevTools shows a POST to `/token` with `grant_type=refresh_token`. SPA does NOT redirect; the next `/api/v1/*` request carries a fresh Bearer (decode at jwt.io: `iat` is new). Restore the lifespan.
5. **Logout** — click "Logout". Expect: redirect to Keycloak's `end_session_endpoint`, then back to `/`. SessionStore cleared. A forced navigation to a guarded route → redirect-to-Keycloak, not error.
6. **Logged-out state** — visit `/clubs` in a fresh tab (same browser). Expect: redirect to Keycloak (cookies may produce silent SSO — that's fine; verify by closing the browser and reopening).
7. **Multi-tab logout** — two authenticated tabs. Logout in tab A. In tab B, click a nav link → redirect to Keycloak within ~5 s.
8. **Public route stays public** — when logged out, `/` (landing) and any `/auth/*` route load without redirecting to Keycloak.
9. **Hard 401 from API** — Keycloak admin: disable `clubadmin1`. In the SPA, click around. The next `/api/v1/*` call returns 401 (server rejects the still-valid-looking JWT) → SPA redirects to Keycloak. Re-enable the user.

If any step fails, the story is not done — surface the failure via `/modernize-rework S-021`.

## Deferred Playwright coverage

The original test plan called for a Playwright e2e spec covering login / logout / silent-refresh / multi-tab / refresh-expired. That harness needs Keycloak Admin REST orchestration + serial-describe scaffolding + a Keycloak-up CI job that does not exist today; the existing Playwright config boots the SPA under `--configuration=mock-auth` for `clubs-crud.spec.ts`. The manual smoke test above is the operator-run substitute. Follow-up story: stand up a real-OIDC Playwright project + CI job.
