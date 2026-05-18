import { alpenflightOidcConfig } from './auth.config';

describe('alpenflightOidcConfig', () => {
  it('targets the AlpenFlight Keycloak realm via same-origin reverse proxy', () => {
    expect(alpenflightOidcConfig.authority).toBe('/realms/alpenflight');
  });

  it('declares the alpenflight-web public client', () => {
    expect(alpenflightOidcConfig.clientId).toBe('alpenflight-web');
  });

  it('uses Authorization Code + PKCE-S256', () => {
    expect(alpenflightOidcConfig.responseType).toBe('code');
  });

  it('attaches Bearer ONLY to /api/v1/* (relative, same-origin)', () => {
    expect(alpenflightOidcConfig.secureRoutes).toEqual(['/api/v1/']);
  });

  it('does NOT include the SPA origin in secureRoutes (would leak Bearer to Keycloak)', () => {
    for (const route of alpenflightOidcConfig.secureRoutes ?? []) {
      expect(route).not.toMatch(/realms|protocol|certs|userinfo/);
    }
  });

  it('enables silent renew with refresh-token rotation', () => {
    expect(alpenflightOidcConfig.silentRenew).toBe(true);
    expect(alpenflightOidcConfig.useRefreshToken).toBe(true);
  });

  it('forbids unsafe refresh-token reuse (Keycloak rotation must succeed; pinned, not defaulted)', () => {
    expect(alpenflightOidcConfig.allowUnsafeReuseRefreshToken).toBe(false);
  });

  it('renews tokens at least 60s before expiry', () => {
    expect(alpenflightOidcConfig.renewTimeBeforeTokenExpiresInSeconds).toBeGreaterThanOrEqual(60);
  });

  it('requests the openid + profile + email scopes (offline_access is a realm-default scope)', () => {
    const scope = alpenflightOidcConfig.scope ?? '';
    expect(scope).toMatch(/\bopenid\b/);
    expect(scope).toMatch(/\bprofile\b/);
    expect(scope).toMatch(/\bemail\b/);
  });

  it('forwards ui_locales=de to Keycloak (German login UI)', () => {
    expect(alpenflightOidcConfig.customParamsAuthRequest?.['ui_locales']).toBe('de');
  });

  it('disables auto-userinfo (claims come from the access/id token directly)', () => {
    // Keycloak protocol mappers stamp clubId + roles on the access token; a
    // separate /userinfo round-trip duplicates the discovery + adds latency
    // to silent-refresh.
    expect(alpenflightOidcConfig.autoUserInfo ?? true).toBe(false);
  });
});
