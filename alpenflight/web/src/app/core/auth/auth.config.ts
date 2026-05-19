import { LogLevel, type OpenIdConfiguration } from 'angular-auth-oidc-client';

/**
 * Single source of truth for the SPA's OIDC client.
 *
 * - `authority` is the absolute issuer URL. CLAUDE.md's "no hardcoded
 *   absolute server URLs" rule applies to the AlpenFlight backend API
 *   (where same-origin + proxy keeps dev / prod URL-shape identical);
 *   OIDC needs the absolute issuer because the library validates it
 *   against the `iss` claim Keycloak stamps on tokens and refuses a
 *   relative / proxy-routed authority on the issuer-mismatch check.
 *   S-041 prod-cutover swaps this for the hosted IdP URL (and / or
 *   the same-origin reverse-proxy alias) — wire via environment.ts at
 *   that point.
 *
 * - `secureRoutes: ['/api/v1/']` keeps the Bearer header off Keycloak's
 *   own JWKS / userinfo paths (would loop) and off any future
 *   `/api/public/v1/*` endpoints.
 *
 * - `silentRenew` + `useRefreshToken` ride Keycloak's rotating refresh-
 *   token flow; `allowUnsafeReuseRefreshToken: false` pinned to match the
 *   realm's `revokeRefreshToken=true` + `refreshTokenMaxReuse=0`.
 *
 * - `customParamsAuthRequest.ui_locales = 'de'` so the Keycloak login UI
 *   renders in German for the AlpenFlight audience.
 */
export const alpenflightOidcConfig: OpenIdConfiguration = {
  // TODO(S-041): swap to env-pinned hosted-IdP URL at prod cutover.
  authority: 'http://localhost:8090/realms/alpenflight',
  clientId: 'alpenflight-web',
  redirectUrl: window.location.origin + '/auth/callback',
  postLogoutRedirectUri: window.location.origin,
  responseType: 'code',
  scope: 'openid profile email',
  silentRenew: true,
  useRefreshToken: true,
  // Defense-in-depth: pin in source rather than rely on the library
  // default. A future major bump that flips the default to `true` would
  // silently break the realm's rotation-and-revoke contract.
  allowUnsafeReuseRefreshToken: false,
  renewTimeBeforeTokenExpiresInSeconds: 60,
  ignoreNonceAfterRefresh: true,
  triggerRefreshWhenIdTokenExpired: false,
  autoUserInfo: false,
  // Suppress the lib's auto-navigate to `postLoginRoute` after callback.
  // `OidcSessionBridge` reads the originally-requested URL from session
  // storage (set by `authGuard` before `authorize()`) and navigates to
  // it explicitly — preserves deep links across the Keycloak round trip.
  triggerAuthorizationResultEvent: true,
  secureRoutes: ['/api/v1/'],
  customParamsAuthRequest: { ui_locales: 'de' },
  logLevel: LogLevel.Warn,
};
