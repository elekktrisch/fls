import { LogLevel, type OpenIdConfiguration } from 'angular-auth-oidc-client';

// ext: Keycloak client_id + realm issuer
const KEYCLOAK_CLIENT_ID = 'alpenflight-web';
const DEV_KEYCLOAK_ISSUER_URL = 'http://localhost:8090/realms/alpenflight';

const CONFIG_ID_PINNED_SO_AUTH_STATE_STORAGE_KEYS_SURVIVE_A_NEW_TAB = KEYCLOAK_CLIENT_ID;

export const alpenflightOidcConfig: OpenIdConfiguration = {
  configId: CONFIG_ID_PINNED_SO_AUTH_STATE_STORAGE_KEYS_SURVIVE_A_NEW_TAB,
  authority: DEV_KEYCLOAK_ISSUER_URL,
  clientId: KEYCLOAK_CLIENT_ID,
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
