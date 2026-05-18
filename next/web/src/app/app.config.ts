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
  DefaultSessionStorageService,
  authInterceptor,
  provideAuth,
  withAppInitializerAuthCheck,
} from 'angular-auth-oidc-client';
import { de_DE, provideNzI18n } from 'ng-zorro-antd/i18n';
import { Subject } from 'rxjs';

import { routes } from './app.routes';
import { alpenflightOidcConfig } from './core/auth/auth.config';
import { OidcSessionBridge } from './core/auth/oidc-session-bridge';
import { MUTATION_BUS, type MutationEvent } from './core/mutation-bus/mutation-bus';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    provideHttpClient(withFetch(), withInterceptors([authInterceptor()])),
    provideAnimationsAsync(),
    provideNzI18n(de_DE),
    provideRouter(routes, withComponentInputBinding(), withViewTransitions()),
    { provide: MUTATION_BUS, useValue: new Subject<MutationEvent>() },
    // Refresh-token + claim state die with the tab, not the browser
    // process. Bounds the XSS-exfiltration window per S-021 security plan.
    { provide: AbstractSecurityStorage, useClass: DefaultSessionStorageService },
    provideAuth({ config: alpenflightOidcConfig }, withAppInitializerAuthCheck()),
    provideAppInitializer(() => {
      // Constructing the bridge registers the userData → SessionStore
      // effect + the SilentRenewFailed subscription before checkAuth fires.
      inject(OidcSessionBridge);
    }),
  ],
};
