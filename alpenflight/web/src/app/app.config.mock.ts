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
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { de_DE, provideNzI18n } from 'ng-zorro-antd/i18n';
import { Subject } from 'rxjs';

import { routes } from './app.routes';
import { provideAlpenflightIcons } from './core/icons/icon-registry';
import { provideAlpenflightI18n } from './core/i18n';
import { MUTATION_BUS, type MutationEvent } from './core/mutation-bus/mutation-bus';
import { SessionStore, type User } from './core/session/session.store';

/*
 * Mock-auth profile (active only under the `mock-auth` angular.json build
 * configuration via fileReplacements). Playwright-CI / no-Keycloak dev
 * convenience: bootstraps `SessionStore` with a synthetic SYSTEM_ADMINISTRATOR
 * principal so the SPA renders without a running Keycloak, and stamps
 * `Bearer mock-sysadmin` on every `/api/v1/*` request.
 *
 * Post-S-026 the backend `MockSecurityConfig` is gone — the live backend
 * rejects the mock bearer with 401 (`ClubsAuthorizationTest
 * .list_with_legacy_mock_auth_header_returns_401` regression-locks the
 * rejection). The Playwright suite stubs the backend via
 * `page.route(...)` so accidental hits against a real backend fail
 * loudly. Tree-shaken out of prod via the `fileReplacements` seam in
 * `angular.json`. The SPA seam re-rips when a real-OIDC Playwright
 * project lands (S-021 follow-up).
 *
 * S-021 ripped the `core/auth/mock-auth.{bootstrap,interceptor}.ts`
 * helper files; the residual mock now lives inline in this config so
 * `app.config.ts` is the single OIDC entry point.
 */

const MOCK_CLUB_ID = '019e30c3-2c00-7001-8000-000000000001';

// Synthetic personId (prefixed `pn-` external form per ADR 0019) so the
// mock principal exercises the S-165 home dashboard's populated state
// (the `personId` filter on /flights). Specs that want the empty-state
// branch return [] for the flights stub.
const MOCK_PERSON_ID = 'pn-019e30c3-2c00-7100-8000-0000000000a5';

const MOCK_USER: User = {
  id: 'mock-sysadmin',
  username: 'mock-sysadmin',
  email: 'mock@local',
  firstName: 'Mock',
  lastName: 'Sysadmin',
  clubId: MOCK_CLUB_ID,
  personId: MOCK_PERSON_ID,
  // Both roles: SYSTEM_ADMINISTRATOR unlocks sysadmin-only screens,
  // CLUB_ADMINISTRATOR unlocks the per-tenant mutation gates
  // (`session.isClubAdmin`, used by e.g. `aircraft-edit`'s canMutate
  // guard). The production model keeps these separate — sysadmin has
  // no clubId — but the mock is the "can drive everything" persona.
  roles: ['SYSTEM_ADMINISTRATOR', 'CLUB_ADMINISTRATOR'],
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
  const session = inject(SessionStore);
  session.login(MOCK_USER, MOCK_CLUB_ID);
  session.bootstrapPrefetch();
}

// LandingComponent injects OidcSecurityService to drive the sign-in
// redirect; under mock-auth there's no Keycloak, so we stub the service.
// `authorize()` records its last-call args to `window.__lastAuthorizeArgs`
// so the Playwright signup spec can assert the customParams shape
// (prompt=create, kc_idp_hint=google, ui_locales=...) without a live
// Keycloak round-trip. Real-OIDC end-to-end lives in the separate
// Keycloak-up project (S-021 follow-up). Narrow stub shape guards
// against a future OidcSecurityService API drift going silent.
//
// S-134 contract for the spec seam:
//   - window.__lastAuthorizeArgs is undefined until authorize() fires.
//   - Each call overwrites; specs clear before exercising.
const MOCK_OIDC_SECURITY_SERVICE: Pick<OidcSecurityService, 'authorize'> = {
  authorize: (configId?: string | undefined, params?: unknown): undefined => {
    type AuthorizeWindow = Window & {
      __lastAuthorizeArgs?: { configId?: string; params?: unknown };
    };
    if (typeof window !== 'undefined') {
      const recordable: { configId?: string; params?: unknown } = { params };
      if (configId !== undefined) recordable.configId = configId;
      (window as AuthorizeWindow).__lastAuthorizeArgs = recordable;
    }
    return undefined;
  },
};

export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    provideHttpClient(withFetch(), withInterceptors([mockAuthInterceptor])),
    provideAnimationsAsync(),
    provideNzI18n(de_DE),
    provideAlpenflightI18n(),
    provideAlpenflightIcons(),
    provideRouter(routes, withComponentInputBinding(), withViewTransitions()),
    { provide: MUTATION_BUS, useValue: new Subject<MutationEvent>() },
    { provide: OidcSecurityService, useValue: MOCK_OIDC_SECURITY_SERVICE },
    provideAppInitializer(mockAuthBootstrap),
  ],
};
