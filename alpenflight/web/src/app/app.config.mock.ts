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
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { de } from 'date-fns/locale';
import { NZ_DATE_LOCALE, de_DE, provideNzI18n } from 'ng-zorro-antd/i18n';
import { Subject, of } from 'rxjs';

import { routes } from './app.routes';
import { provideAlpenflightIcons } from './core/icons/icon-registry';
import { provideAlpenflightI18n } from './core/i18n';
import { MUTATION_BUS, type MutationEvent } from './core/mutation-bus/mutation-bus';
import { SessionStore, type User } from './core/session/session.store';

const MOCK_CLUB_ID = '019e30c3-2c00-7001-8000-000000000001';

const MOCK_PERSON_ID = 'pn-019e30c3-2c00-7100-8000-0000000000a5';

const SEEDED_BERN_BELP_HOMEBASE_LOCATION_ID = 'loc-019e30c3-2c00-7001-8000-00000000c001';

const MOCK_USER: User = {
  id: 'mock-sysadmin',
  username: 'mock-sysadmin',
  email: 'mock@local',
  firstName: 'Mock',
  lastName: 'Sysadmin',
  clubId: MOCK_CLUB_ID,
  personId: MOCK_PERSON_ID,
  homebaseLocationId: SEEDED_BERN_BELP_HOMEBASE_LOCATION_ID,
  roles: ['SYSTEM_ADMINISTRATOR', 'CLUB_ADMINISTRATOR'],
};

const mockAuthInterceptor: HttpInterceptorFn = (req, next) => {
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

const MOCK_OIDC_SECURITY_SERVICE: Pick<OidcSecurityService, 'authorize' | 'logoff'> = {
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
  logoff: () => of({}) as ReturnType<OidcSecurityService['logoff']>,
};

export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    provideHttpClient(withFetch(), withInterceptors([mockAuthInterceptor])),
    provideAnimationsAsync(),
    provideNzI18n(de_DE),
    { provide: NZ_DATE_LOCALE, useValue: de },
    provideAlpenflightI18n(),
    provideAlpenflightIcons(),
    provideRouter(routes, withComponentInputBinding()),
    { provide: MUTATION_BUS, useValue: new Subject<MutationEvent>() },
    { provide: OidcSecurityService, useValue: MOCK_OIDC_SECURITY_SERVICE },
    provideAppInitializer(mockAuthBootstrap),
  ],
};
