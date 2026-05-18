---
id: S-021
title: Angular OIDC client (Authorization Code + PKCE)
epic: E-03
status: in_progress
started_at: 2026-05-18
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
SPA-side counterpart to S-020. Replaces the legacy "POST /Token + sessionStorage + `$http.defaults`" model with proper OIDC PKCE + silent refresh.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Install `angular-auth-oidc-client`; configure against `http://localhost:8090/realms/alpenflight` (issuer per ADR 0007 + `next/auth/README.md`; client-id `alpenflight-web`, public + PKCE-S256, redirect URIs `http://localhost:{4200,3000}/*`).
- [ ] Wire `LoginCallback` route.
- [ ] HttpInterceptor that attaches the access token to outgoing requests to `/api/v1/**`.
- [ ] Silent refresh configuration (the library handles the iframe + refresh-token rotation).
- [ ] Wire `SessionStore` (from S-006) to read from the OIDC service's signals/observables.
- [ ] Route guard that redirects unauthenticated users to login.
- [ ] Logout flow: clear tokens, post-logout redirect URL.
- [ ] **Boyscout — Keycloak realm config:** edit `next/auth/realm-export.json` so `offline_access` is a **default** client scope on `alpenflight-web` (not optional). Refresh-tokens then issue automatically; future clients (mobile, integrations) inherit the same default without per-client setup.
- [ ] **Boyscout — delete `/hello`:** strip the `/hello` route + `HelloStore` + `HelloController` + the `hello/` package on both sides. It was day-one scaffolding; nothing real depends on it. Removes a public surface that would otherwise need either an auth gate or a public API endpoint.
- [ ] **Manual smoke test pass** — walk the 9-step checklist in `## Test plan` § "Manual smoke test" against a `dev-up-full.sh` bring-up. Confirm each step in the done report. Story does not mark-done until this passes.

## Notes
This story is L because OIDC + refresh + SPA interceptors + route guards + store wiring is genuinely a lot. Tasks split it. Test with both happy path (login → fetch → silent refresh → fetch) and failure paths (expired refresh → redirect to login).

Replaces legacy `core/AuthService.js`.

<!-- modernize-refine: start -->

## Design notes

1. **Library** — keep `angular-auth-oidc-client`. Standalone `provideAuth()` + signal-based `authenticated`/`userData` (post-v17) match the zoneless signal-first posture. No swap to `angular-oauth2-oidc`.
2. **Provider seam** — Vite `fileReplacements` on `environment.ts`. `useMockAuth: true` loads `app.config.mock.ts` (mock interceptor + bootstrap, no `provideAuth`); `useMockAuth: false` provides the real OIDC. Tree-shaking drops the mock path under prod builds; backend-down dev keeps the mock path available.
3. **SessionStore population** — APP_INITIALIZER `core/auth/oidc-session-bridge.ts` calls `oidcSecurity.checkAuth()` once on boot, then an `effect()` watches `oidcSecurity.userData()` and pipes `{ sub, email, clubId, roles }` into `SessionStore.setUser(...)`. `SessionStore` stays the only read seam for app code; no component injects `OidcSecurityService` except the auth feature.
4. **Claim mapping** — Keycloak emits roles at `realm_access.roles[]` (nested), not as a flat `roles` claim. The mapper reads `userData()?.realm_access?.roles` or decodes `oidcSecurity.getAccessToken()`. `clubId` comes from a top-level claim per ADR 0007 + the realm's protocol mapper.
5. **Token attachment** — `secureRoutes: ['/api/v1/']` only (same-origin, relative; per CLAUDE.md "no hardcoded absolute server URLs"). Public-flow endpoints relocate under `/api/public/v1/` so the prefix mismatch keeps Bearer off them — the library has no exclusion list. Keycloak's own JWKS / userinfo URLs are NOT in `secureRoutes` (would loop).
6. **Refresh + 401** — `silentRenew: true` + `useRefreshToken: true`. `offline_access` ships as a **default** client scope on the realm (operator decision 2026-05-18; boyscout-edited in this PR) so the SPA does not have to request it explicitly; future clients inherit. Renew window 60s before expiry. `PublicEventsService.SilentRenewFailed` → `SessionStore.clear()` then `oidcSecurity.authorize()` (hard redirect to Keycloak). Hard 401 from `/api/v1/*` → hard-redirect, NOT refresh-and-retry; silent renew already owns the refresh state machine.
7. **Route guard** — `authGuard: CanActivateFn` reads `oidcSecurity.authenticated()` signal; redirects unauthenticated users via `oidcSecurity.authorize()`. Routes carrying `data: { publicAccess: true }` (landing, public-flow, `/callback`) are unguarded.
8. **Logout** — `SessionStore.clear()` runs **synchronously first**, then `oidcSecurity.logoff({ postLogoutRedirectUri })`. Clearing first prevents the guard re-redirecting mid-logout on a stale store. `logoff()` is RP-initiated (Keycloak `end_session_endpoint` with `id_token_hint`), not local-only.
9. **Mock-auth rip-out** — same PR. `mock-auth.bootstrap.ts` + `mock-auth.interceptor.ts` deleted; mock persists only as `app.config.mock.ts` behind `fileReplacements`. Mock was always staged for retirement once S-021 landed.

ADR 0022 directive 2 (schema business logic): N/A — pure SPA story.

## Edge cases & hidden requirements

- **`clubId` claim absent** — federated / not-yet-imported users have no `clubId` in the JWT. Operator decision 2026-05-18: **let them in with `clubId: null`**; the server-side resolver (S-022) falls back to a DB lookup by `keycloak_sub` / email. `SessionStore.User.clubId` widens to `string | null`; UI surfaces "no club selected" in the nav bar while the lookup resolves. Matches the cross-IdP portability rule per memory `[[clubid-resolution-not-only-jwt]]`.
- **`realm_access.roles[]` nesting** — easy implementer miss; the mapper must un-nest, not read a top-level `roles` claim.
- **Two-tab silent-renew race** — Keycloak realm pins `revokeRefreshToken=true` + `refreshTokenMaxReuse=0`; concurrent renews produce `invalid_grant` on the second tab. A `PublicEventsService.SilentRenewFailed` subscriber MUST own the silent path — the 401-from-API redirect is too late (tab is authenticated-but-stale until the next API call).
- **`mockAuthInterceptor` + `mockAuthBootstrap` removed atomically** with the OIDC wiring — partial removal leaves the guard returning `false` forever.
- **`secureRoutes` excludes Keycloak's own URLs** — discovery / JWKS / userinfo never receive an AlpenFlight Bearer.
- **Logout idempotency** — double-click is fine; library's `logoff()` no-ops when already logged out.
- **`de_DE` locale handoff** — `customParamsAuthRequest: { ui_locales: 'de' }` so the Keycloak login page renders in German. Without it, the Keycloak realm default wins.
- **`realm.post.logout.redirect.uris`** must allowlist the SPA's post-logout URL on the `alpenflight-web` client (verify against S-019 realm export).
- **Token storage** — `storage: sessionStorage`. Refresh dies with the tab; tab-lifetime XSS window only. No localStorage (cross-tab leakage risk).
- **`/hello` route deleted** — operator decision 2026-05-18 to strip day-one scaffolding rather than gate-or-public-ify it. Removes the cold-start 401 race entirely.

## Security plan

- **Public client + PKCE-S256** — `alpenflight-web` is `publicClient=true`, no secret. Verify in S-019 realm export.
- **Token storage = sessionStorage**, not localStorage / in-memory. Bounds refresh-token theft window to tab lifetime.
- **Refresh-token rotation** — `useRefreshToken: true`, `allowUnsafeReuseRefreshToken: false` (default). Keycloak rotates on use; reuse-detection revokes the chain.
- **`secureRoutes` allowlist** — `['/api/v1/']` only. No Keycloak, no GlitchTip (S-034), no OGN / Proffix. Threat: Bearer leaked to third-party origin.
- **PII redaction in observability** — GlitchTip breadcrumbs scrub the `Authorization` header; user context is `user.id = sub` only (no `email` / `name`). Threat: PII / token leak via error tracker.
- **CSP** — S-041 reverse proxy sets `connect-src 'self' <keycloak>`, `form-action 'self' <keycloak>`, `frame-ancestors 'none'`, no `unsafe-inline`. Threat: SSO-callback spoofing / clickjacked consent.
- **Logout completeness** — RP-initiated `logoff()` to Keycloak's `end_session_endpoint`, not local-only token clear. Threat: residual SSO cookie re-authenticates instantly on shared device.
- **CORS** — production same-origin (CLAUDE.md cross-cutting rule); dev `localhost:4200`/`3000` listed in Keycloak `Web Origins`. No `*`.
- **Hard-401 redirect** — `oidcSecurityService.authorize()` to Keycloak `/auth`. No homemade `/login` form, ever. Threat: phishing-clone login pages.
- **`clubId` is SPA-cosmetic, server-authoritative** — `SessionStore` reads it for routing / UI display only. Every API call's tenant scope is set server-side by S-022's `ClubTenantIdentifierResolver` reading the validated JWT (S-020). SPA never sends `clubId` as a request parameter, header, or body field.
- **SPA auth events are NOT audit-log records** — S-027 audit log is server-side only. Console / GlitchTip breadcrumbs for login / silent-refresh-failed / logout are diagnostic, not forensic.

## Test plan

- **Vitest unit** (per `[[fe-tests-unit-for-logic-playwright-for-dom]]`): claims-to-`SessionStore` mapper (happy + missing `clubId` + missing roles + nested `realm_access`); `PublicEventsService.SilentRenewFailed` handler clears store + initiates re-auth; `authInterceptor()` attaches Bearer to `/api/v1/*` only (via `TestBed` + `HttpTestingController`); mock-auth profile asserts `OidcSecurityService` is NOT provided.
- **Playwright e2e** — one new spec `next/web/e2e/tests/auth/oidc-auth.spec.ts` covering login (PKCE redirect → callback → guarded route + nav-user populated), logout (RP-initiated end_session → landing), 401-redirect (route-mock 401 → login), silent-refresh (Keycloak `accessTokenLifespan` shortened via Admin REST in fixture), refresh-expired (SSO session shortened), multi-tab logout detection (two `browser.newContext()` sharing `storageState`).
- **Realm users** already in `next/auth/realm-export.json` — `clubadmin1` carries `CLUB_ADMINISTRATOR` + real `clubId: club-1`. No realm-export change needed.
- **Fixtures** — Playwright `globalSetup` logs in once + saves `storageState` for reuse. Token-lifetime patches run via Keycloak Admin REST in `test.beforeAll` / `afterAll`; admin creds in `.env.test` (not committed). Silent-refresh + refresh-expired specs run in `test.describe.serial` to avoid concurrent realm mutation races.
- **Deferred coverage:** role-based route guards (S-026); cross-IdP `clubId` fallback via DB lookup (S-022 already shipped the resolver path).
- **No parity test** — replaces legacy `POST /Token + sessionStorage`; no behavioral oracle to diff against.

### Manual smoke test (must pass before mark-done)

The implementer must run this checklist by hand and confirm each step before flipping `status: done`. Asserts what the automated tests can't easily catch — the actual Keycloak round-trip, the visible nav state, the redirect URLs.

**Bring-up:** `next/ops/dev-up-full.sh` (per memory `[[use-dev-up-full-not-compose]]`). Wait for the spinner. Confirm: Keycloak on `http://localhost:8090`, backend on the configured port, Postgres on `5432`.

1. **Cold-start login** — open an incognito window to `http://localhost:4200/clubs`. Expect: immediate redirect to Keycloak (URL contains `/realms/alpenflight/protocol/openid-connect/auth?response_type=code...&code_challenge=...`). Login UI renders in **German** (the `ui_locales=de` handoff worked). Log in as `clubadmin1` / `clubadmin1`. Expect: redirect back to `/clubs` with the clubs list rendered; nav bar shows the user's name + the club name.
2. **Authenticated API call** — open DevTools Network. Click a club. The `GET /api/v1/clubs/{id}` request should carry `Authorization: Bearer eyJ…`. Confirm the response is 200 with the club body.
3. **Bearer NOT attached to non-API URLs** — same DevTools view: navigate around. Static assets (`/main-*.js`, `/styles-*.css`), the Keycloak callback POST, any third-party request (if any) must **not** carry `Authorization`.
4. **Silent refresh visible** — in Keycloak admin UI (`http://localhost:8090` → `master` realm → `alpenflight` → Realm settings → Tokens), shorten `Access Token Lifespan` to **60 seconds**. Save. Reload the SPA. Wait 70 seconds while keeping the page open + idle. DevTools shows a single POST to `/realms/alpenflight/protocol/openid-connect/token` with `grant_type=refresh_token`. The SPA does NOT redirect; the user stays logged in. Then click around: the next `/api/v1/*` request carries the **new** Bearer (decode it on jwt.io — the `iat` is fresh). Restore the lifespan to the default (5 min) afterwards.
5. **Logout** — click "Logout" in the nav. Expect: redirect to Keycloak's `end_session_endpoint`, then back to `/`. Nav user gone; SessionStore cleared. Click "Logout" again (it shouldn't be visible, but force a navigation to a guarded route) → expect redirect-to-login, not error.
6. **Logged-out state** — directly visit `http://localhost:4200/clubs` in a fresh tab (same browser context, no incognito). Expect: redirect to Keycloak's login (the cookies from step 1 may still produce silent SSO — that's fine; verify by closing the browser and reopening).
7. **Multi-tab logout** — open two tabs both authenticated. Logout in tab A. In tab B, click any nav link → expect redirect to login within ~5 s.
8. **Public route stays public** — when logged out, `/landing` (S-097) and any `/public-flow/*` routes must load without redirecting to Keycloak. Confirm by navigating directly when logged out.
9. **Hard 401 from API** — temporarily revoke the user in Keycloak admin (Users → `clubadmin1` → set Enabled to OFF). In the SPA, click around. The next `/api/v1/*` call returns 401 (server rejects the still-valid-looking JWT against Keycloak introspection — or the next silent refresh fails). Expect: redirect to login. Re-enable the user afterwards.

If any step fails, the story is not done. The Playwright suite covers most of these but the manual run catches drift in the realm config + the locale handoff + DevTools Network observability that no Playwright assertion replaces.

## Performance plan

- **Per-request interceptor overhead** — `authInterceptor` reads the cached Bearer synchronously. Sub-millisecond; no budget. Anti-pattern to flag in review: a "is token expiring?" check inside the interceptor — silent renew owns the refresh path.
- **First-paint cold-start** — one Keycloak round-trip on `checkAuth()` (zero if cached tokens still valid). Budget: p95 < 800ms for the auth resolve; the SPA shell MUST render a skeleton during, never a white screen. Counts against the < 3s page-load NFR.
- **Silent refresh** — background POST against same-origin Keycloak; does not block user requests.
- **PWA offline** — interceptor attaches the cached Bearer; the request fails (no network); ADR 0014 queue catches. Interceptor MUST NOT call `forceRefreshSession()` when `navigator.onLine === false` (would loop on every queued write).
- **Bundle** — ~80kb gzipped; mock-auth path tree-shakes under prod `fileReplacements`. Acceptable.

<!-- modernize-refine: end -->
