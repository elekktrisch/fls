import {
  provideHttpClient,
  withFetch,
  withInterceptors,
  type HttpInterceptorFn,
} from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideZonelessChangeDetection,
} from '@angular/core';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideRouter, withComponentInputBinding, withViewTransitions } from '@angular/router';
import { de_DE, provideNzI18n } from 'ng-zorro-antd/i18n';
import { Subject } from 'rxjs';

import { routes } from './app.routes';
import { MUTATION_BUS, type MutationEvent } from './core/mutation-bus/mutation-bus';
import { SessionStore, type User } from './core/session/session.store';

/*
 * Mock-auth profile (active only under the `mock-auth` angular.json build
 * configuration via fileReplacements). Dev convenience for working without
 * a running Keycloak: every `/api/v1/*` request carries `Bearer
 * mock-sysadmin`, which the backend's MockAuthenticationFilter
 * (S-048, profile `mock-auth`) decodes into a SYSTEM_ADMINISTRATOR
 * principal. Tree-shaken out of prod via the seam in `angular.json`.
 *
 * S-021 ripped the `core/auth/mock-auth.{bootstrap,interceptor}.ts`
 * helper files; the residual mock now lives inline in this config so
 * `app.config.ts` is the single OIDC entry point.
 */

const MOCK_CLUB_ID = '019e30c3-2c00-7001-8000-000000000001';

const MOCK_USER: User = {
  id: 'mock-sysadmin',
  username: 'mock-sysadmin',
  email: 'mock@local',
  firstName: 'Mock',
  lastName: 'Sysadmin',
  clubId: MOCK_CLUB_ID,
  roles: ['SYSTEM_ADMINISTRATOR'],
};

const mockAuthInterceptor: HttpInterceptorFn = (req, next) => {
  // Prefix match (same shape as production `authInterceptor()` matching
  // `secureRoutes`). `includes()` would attach the literal mock Bearer
  // to any URL containing `/api/v1/` as a substring (e.g. a
  // proxy-with-redirect URL).
  if (!req.url.startsWith('/api/v1/')) {
    return next(req);
  }
  return next(req.clone({ setHeaders: { Authorization: 'Bearer mock-sysadmin' } }));
};

function mockAuthBootstrap(): void {
  inject(SessionStore).login(MOCK_USER, MOCK_CLUB_ID);
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    provideHttpClient(withFetch(), withInterceptors([mockAuthInterceptor])),
    provideAnimationsAsync(),
    provideNzI18n(de_DE),
    provideRouter(routes, withComponentInputBinding(), withViewTransitions()),
    { provide: MUTATION_BUS, useValue: new Subject<MutationEvent>() },
    provideAppInitializer(mockAuthBootstrap),
  ],
};
