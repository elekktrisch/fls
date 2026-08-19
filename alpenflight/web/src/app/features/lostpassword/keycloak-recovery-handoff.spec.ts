import { describe, expect, it, vi } from 'vitest';

import type { AuthOptions } from 'angular-auth-oidc-client';

import {
  type KeycloakAuthorizeEntryPoint,
  handOffToTheKeycloakLoginPageWhoseThemeCarriesTheForgotPasswordLink,
} from './keycloak-recovery-handoff';

interface RecordedAuthorizeCall {
  configId: string | undefined;
  authOptions: AuthOptions | undefined;
}

function recordingKeycloak(): {
  keycloak: KeycloakAuthorizeEntryPoint;
  calls: RecordedAuthorizeCall[];
} {
  const calls: RecordedAuthorizeCall[] = [];
  return {
    calls,
    keycloak: {
      authorize(configId?: string, authOptions?: AuthOptions): void {
        calls.push({ configId, authOptions });
      },
    },
  };
}

describe('handOffToTheKeycloakLoginPageWhoseThemeCarriesTheForgotPasswordLink', () => {
  it('starts the standard authorize flow so the login page renders the forgot-password link', () => {
    const { keycloak, calls } = recordingKeycloak();

    handOffToTheKeycloakLoginPageWhoseThemeCarriesTheForgotPasswordLink(keycloak, 'de');

    expect(calls).toHaveLength(1);
    expect(calls[0]?.configId).toBeUndefined();
  });

  it('passes the active locale as ui_locales so Keycloak renders the reset chrome translated', () => {
    const { keycloak, calls } = recordingKeycloak();

    handOffToTheKeycloakLoginPageWhoseThemeCarriesTheForgotPasswordLink(keycloak, 'fr');

    expect(calls[0]?.authOptions?.customParams).toEqual({ ui_locales: 'fr' });
  });

  it('sends no reset-credentials deep link, because that endpoint needs a Keycloak tab session', () => {
    const { keycloak, calls } = recordingKeycloak();

    handOffToTheKeycloakLoginPageWhoseThemeCarriesTheForgotPasswordLink(keycloak, 'it');

    expect(JSON.stringify(calls[0]?.authOptions ?? {})).not.toContain('reset-credentials');
  });

  it('lets an authorize failure reach the caller, so the page can show its unreachable message', () => {
    const authorizeThatFails = vi.fn(() => {
      throw new Error('storage unavailable');
    });

    expect(() =>
      handOffToTheKeycloakLoginPageWhoseThemeCarriesTheForgotPasswordLink(
        { authorize: authorizeThatFails },
        'en',
      ),
    ).toThrow('storage unavailable');
  });
});
