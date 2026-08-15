import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  type Provider,
  inject,
  provideAppInitializer,
  provideZonelessChangeDetection,
} from '@angular/core';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideRouter, withComponentInputBinding } from '@angular/router';
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

const OIDC_STORAGE_OVERRIDE_THAT_MUST_BE_LISTED_AFTER_PROVIDE_AUTH: Provider = {
  provide: AbstractSecurityStorage,
  useClass: DefaultLocalStorageService,
};

function constructRootSingletonsWhoseConstructorsRegisterSessionEffects(): void {
  inject(OidcSessionBridge);
  inject(MeEventsService);
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    provideHttpClient(withFetch(), withInterceptors([authInterceptor()])),
    provideAnimationsAsync(),
    provideNzI18n(de_DE),
    { provide: NZ_DATE_LOCALE, useValue: de },
    provideAlpenflightI18n(),
    provideAlpenflightIcons(),
    provideRouter(routes, withComponentInputBinding()),
    { provide: MUTATION_BUS, useValue: new Subject<MutationEvent>() },
    provideAuth({ config: alpenflightOidcConfig }, withAppInitializerAuthCheck()),
    OIDC_STORAGE_OVERRIDE_THAT_MUST_BE_LISTED_AFTER_PROVIDE_AUTH,
    provideAppInitializer(constructRootSingletonsWhoseConstructorsRegisterSessionEffects),
  ],
};
