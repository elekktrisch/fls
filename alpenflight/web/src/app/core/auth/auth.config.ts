import { LogLevel, type OpenIdConfiguration } from 'angular-auth-oidc-client';

export const alpenflightOidcConfig: OpenIdConfiguration = {
  configId: 'alpenflight-web',
  authority: 'http://localhost:8090/realms/alpenflight',
  clientId: 'alpenflight-web',
  redirectUrl: window.location.origin + '/auth/callback',
  postLogoutRedirectUri: window.location.origin,
  responseType: 'code',
  scope: 'openid profile email',
  silentRenew: true,
  useRefreshToken: true,
  allowUnsafeReuseRefreshToken: false,
  renewTimeBeforeTokenExpiresInSeconds: 60,
  ignoreNonceAfterRefresh: true,
  triggerRefreshWhenIdTokenExpired: false,
  autoUserInfo: false,
  triggerAuthorizationResultEvent: true,
  secureRoutes: ['/api/v1/'],
  customParamsAuthRequest: { ui_locales: 'de' },
  logLevel: LogLevel.Warn,
};
