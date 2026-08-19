import type { AuthOptions } from 'angular-auth-oidc-client';

export interface KeycloakAuthorizeEntryPoint {
  authorize(configId?: string, authOptions?: AuthOptions): void;
}

export function handOffToTheKeycloakLoginPageWhoseThemeCarriesTheForgotPasswordLink(
  keycloak: KeycloakAuthorizeEntryPoint,
  uiLocale: string,
): void {
  keycloak.authorize(undefined, { customParams: { ui_locales: uiLocale } });
}
