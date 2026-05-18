import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  provideAppInitializer,
  provideZonelessChangeDetection,
} from '@angular/core';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideRouter, withComponentInputBinding, withViewTransitions } from '@angular/router';
import { de_DE, provideNzI18n } from 'ng-zorro-antd/i18n';
import { Subject } from 'rxjs';

import { routes } from './app.routes';
import { mockAuthBootstrap } from './core/auth/mock-auth.bootstrap';
import { mockAuthInterceptor } from './core/auth/mock-auth.interceptor';
import { MUTATION_BUS, type MutationEvent } from './core/mutation-bus/mutation-bus';

// Activated only under the `mock-auth` angular.json configuration. Real OIDC
// is provided by `app.config.ts` under the default + production configs. See
// `next/web/src/app/core/auth/README.md` for the dev-vs-prod toggle story.
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
