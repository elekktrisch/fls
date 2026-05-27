import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideZonelessChangeDetection,
} from '@angular/core';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideRouter, withComponentInputBinding, withViewTransitions } from '@angular/router';
import {
  AbstractSecurityStorage,
  DefaultLocalStorageService,
  authInterceptor,
  provideAuth,
  withAppInitializerAuthCheck,
} from 'angular-auth-oidc-client';
import { de_DE, provideNzI18n } from 'ng-zorro-antd/i18n';
import { Subject } from 'rxjs';

import { routes } from './app.routes';
import { alpenflightOidcConfig } from './core/auth/auth.config';
import { OidcSessionBridge } from './core/auth/oidc-session-bridge';
import { provideAlpenflightIcons } from './core/icons/icon-registry';
import { provideAlpenflightI18n } from './core/i18n';
import { MUTATION_BUS, type MutationEvent } from './core/mutation-bus/mutation-bus';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    provideHttpClient(withFetch(), withInterceptors([authInterceptor()])),
    provideAnimationsAsync(),
    provideNzI18n(de_DE),
    provideAlpenflightI18n(),
    provideAlpenflightIcons(),
    provideRouter(routes, withComponentInputBinding(), withViewTransitions()),
    { provide: MUTATION_BUS, useValue: new Subject<MutationEvent>() },
    // OIDC state (refresh token, auth-request state token, nonce) in
    // localStorage so the verify-email round-trip works: the link in the
    // Mailpit email opens in a NEW tab — fresh sessionStorage — and
    // angular-auth-oidc-client's state-matcher would otherwise raise
    // "could not find matching config for state" at /auth/callback.
    // localStorage is shared across same-origin tabs in the same browser.
    // Trade-off vs the original S-021 sessionStorage choice: tokens
    // survive a tab close; the XSS-exfiltration window widens accordingly,
    // mitigated by the realm's short access-token lifespan + refresh-token
    // rotation + no-reuse (ADR 0007). Logout still clears via storage.clear().
    { provide: AbstractSecurityStorage, useClass: DefaultLocalStorageService },
    provideAuth({ config: alpenflightOidcConfig }, withAppInitializerAuthCheck()),
    provideAppInitializer(() => {
      // Constructing the bridge registers the userData → SessionStore
      // effect + the SilentRenewFailed subscription before checkAuth fires.
      inject(OidcSessionBridge);
    }),
  ],
};
