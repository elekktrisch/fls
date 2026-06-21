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
import { de } from 'date-fns/locale';
import { NZ_DATE_LOCALE, de_DE, provideNzI18n } from 'ng-zorro-antd/i18n';
import { Subject } from 'rxjs';

import { routes } from './app.routes';
import { alpenflightOidcConfig } from './core/auth/auth.config';
import { OidcSessionBridge } from './core/auth/oidc-session-bridge';
import { MeEventsService } from './core/events';
import { provideAlpenflightIcons } from './core/icons/icon-registry';
import { provideAlpenflightI18n } from './core/i18n';
import { MUTATION_BUS, type MutationEvent } from './core/mutation-bus/mutation-bus';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    provideHttpClient(withFetch(), withInterceptors([authInterceptor()])),
    provideAnimationsAsync(),
    provideNzI18n(de_DE),
    // Activates ng-zorro's date-fns adapter so the date pickers can deserialise
    // manual keyboard input in the custom dd.MM.yyyy format (the default
    // DatePipe adapter only formats, never parses). LocaleService re-points it
    // per active locale.
    { provide: NZ_DATE_LOCALE, useValue: de },
    provideAlpenflightI18n(),
    provideAlpenflightIcons(),
    provideRouter(routes, withComponentInputBinding(), withViewTransitions()),
    { provide: MUTATION_BUS, useValue: new Subject<MutationEvent>() },
    provideAuth({ config: alpenflightOidcConfig }, withAppInitializerAuthCheck()),
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
    //
    // MUST come AFTER provideAuth(...) — provideAuth registers its own
    // default AbstractSecurityStorage (sessionStorage); the last provider
    // for a token wins, so our override has to land after to take effect.
    { provide: AbstractSecurityStorage, useClass: DefaultLocalStorageService },
    provideAppInitializer(() => {
      // Constructing the bridge registers the userData → SessionStore
      // effect + the SilentRenewFailed subscription before checkAuth fires.
      inject(OidcSessionBridge);
      // Constructing the SSE client registers its session-gated effect (open
      // on authenticated, close on logout) once per app — NOT per component
      // (S-176 / J-3 T-06). Its `GET /api/v1/me/events` stream stays dormant
      // until the session authenticates.
      inject(MeEventsService);
    }),
  ],
};
