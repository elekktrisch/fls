import { LogLevel, type OpenIdConfiguration } from 'angular-auth-oidc-client';

/**
 * Single source of truth for the SPA's OIDC client.
 *
 * - `authority: '/realms/alpenflight'` is **relative** — CLAUDE.md forbids
 *   hardcoded absolute server URLs in app code. In dev, `proxy.conf.json`
 *   routes `/realms/*` to the Keycloak container; in prod the reverse
 *   proxy (S-041) does the same.
 *
 * - `secureRoutes: ['/api/v1/']` keeps the Bearer header off Keycloak's
 *   own JWKS / userinfo paths (would loop) and off any future
 *   `/api/public/v1/*` endpoints.
 *
 * - `silentRenew` + `useRefreshToken` ride Keycloak's rotating refresh-
 *   token flow; `allowUnsafeReuseRefreshToken` defaults to `false`, which
 *   matches the realm's `revokeRefreshToken=true` + `refreshTokenMaxReuse=0`.
 *
 * - `customParamsAuthRequest.ui_locales = 'de'` so the Keycloak login UI
 *   renders in German for the AlpenFlight audience.
 */
export const alpenflightOidcConfig: OpenIdConfiguration = {
  authority: '/realms/alpenflight',
  clientId: 'alpenflight-web',
  redirectUrl:
    typeof window !== 'undefined' ? window.location.origin + '/auth/callback' : '/auth/callback',
  postLogoutRedirectUri: typeof window !== 'undefined' ? window.location.origin : '/',
  responseType: 'code',
  scope: 'openid profile email',
  silentRenew: true,
  useRefreshToken: true,
  renewTimeBeforeTokenExpiresInSeconds: 60,
  ignoreNonceAfterRefresh: true,
  triggerRefreshWhenIdTokenExpired: false,
  autoUserInfo: false,
  secureRoutes: ['/api/v1/'],
  customParamsAuthRequest: { ui_locales: 'de' },
  logLevel: LogLevel.Warn,
};
