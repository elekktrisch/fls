# core/auth — OIDC wiring

S-021 landed: the SPA is an OIDC public client against Keycloak
(`alpenflight-web`, Authorization Code + PKCE-S256). Everything in this
folder is the integration seam between `angular-auth-oidc-client` and
the application's `SessionStore`.

## What's here

| File | Role |
|---|---|
| `auth.config.ts` | Single source of truth for the OIDC `OpenIdConfiguration` (authority, secureRoutes, silent renew, ui_locales). |
| `oidc-claims.ts` | Pure mapper: Keycloak claim payload → `SessionStore.User`. Drops unknown realm roles; tolerates a missing `clubId`. |
| `oidc-session-bridge.ts` | Wires `oidcSecurity.userData()` signal + `PublicEventsService` events into `SessionStore`. Single seam — no other component injects `OidcSecurityService`. |
| `auth-callback.page.ts` | Landing page for the `/auth/callback` redirect. The library handles the token exchange; this page renders a placeholder. |
| `logout.page.ts` | RP-initiated logout — clears SessionStore synchronously, then `oidcSecurity.logoff()`. |
| `auth.routes.ts` | `/auth/callback` + `/auth/logout` routes, both `publicAccess: true`. |

## How auth flows

```
   user hits /clubs (private)
        │
        ▼
   authGuard reads SessionStore.isAuthenticated()
        │
   ┌────┴────┐
   │ true    │ false
   ▼         ▼
 render    oidcSecurity.authorize() → Keycloak /auth?response_type=code&code_challenge=…
                                    │
                                    ▼ (login UI in German via ui_locales=de)
                              redirect → /auth/callback?code=…&state=…
                                    │
                                    ▼
                      withAppInitializerAuthCheck → checkAuth()
                                    │
                                    ▼
                       userData() signal updates
                                    │
                                    ▼
                OidcSessionBridge effect → mapClaimsToUser → SessionStore.login(user, clubId)
                                    │
                                    ▼
                          guard re-evaluates, routes to /clubs
```

Silent refresh: `silentRenew: true` + `useRefreshToken: true`. The library
posts to `/realms/.../token` 60s before the access token expires and
swaps in the new pair. On rotation failure
(`PublicEventsService.SilentRenewFailed`), the bridge clears
SessionStore first (avoids the route guard re-rendering on stale state)
and then triggers `authorize()` for a fresh login.

## Mock-auth seam (Playwright-CI / no-Keycloak dev convenience)

S-026 deleted the backend `MockSecurityConfig` — the production OAuth2
resource server is the only filter chain on the wire. The SPA seam
stays alive as a Playwright-CI affordance: the suite stubs the backend
via `page.route(...)`, so the SPA boots under the `mock-auth`
angular.json configuration without a running Keycloak.
`fileReplacements` swap `src/app/app.config.ts` →
`src/app/app.config.mock.ts`, which stamps `Authorization: Bearer
mock-sysadmin` on every `/api/v1/*` request.

If a request escapes the route stub and reaches the real backend, that
backend rejects it with 401 — the bearer is not a valid JWT. The
rejection is intentional: it surfaces accidental configuration drift
loudly rather than silently authenticating. The SPA seam re-rips when
a real-OIDC Playwright project lands (S-021 follow-up).

Run mock-auth:

```bash
pnpm ng serve --configuration=mock-auth
# OR full bring-up
bash next/ops/dev-up-full.sh
```

## Security posture (per S-021 security plan)

- `alpenflight-web` is `publicClient=true` — no secret, PKCE-S256 only.
- Tokens stored in `sessionStorage` (`AbstractSecurityStorage` → `DefaultSessionStorageService`). Bounds the XSS exfiltration window to the tab lifetime.
- `secureRoutes: ['/api/v1/']` — Bearer attaches only to AlpenFlight API calls. Never to Keycloak's own JWKS / userinfo (would loop) nor to any future `/api/public/v1/*`.
- Refresh-token rotation is mandatory (`allowUnsafeReuseRefreshToken: false`, matching realm `revokeRefreshToken=true`).
- Logout is RP-initiated against Keycloak's `end_session_endpoint`. Local-only token clear leaves the SSO cookie live → instant re-authentication on shared devices.
- `clubId` claim is SPA-cosmetic. Every server call's tenant scope is set by `ClubTenantIdentifierResolver` (S-022) reading the validated JWT — the SPA never sends `clubId` on the wire.
