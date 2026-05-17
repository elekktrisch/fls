import { provideHttpClient, withFetch } from '@angular/common/http';
import { ApplicationConfig, provideZonelessChangeDetection } from '@angular/core';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideRouter, withComponentInputBinding, withViewTransitions } from '@angular/router';
import { de_DE, provideNzI18n } from 'ng-zorro-antd/i18n';
import { Subject } from 'rxjs';

import { routes } from './app.routes';
import { MUTATION_BUS, type MutationEvent } from './core/mutation-bus/mutation-bus';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    provideHttpClient(withFetch()),
    provideAnimationsAsync(),
    provideNzI18n(de_DE),
    provideRouter(routes, withComponentInputBinding(), withViewTransitions()),
    { provide: MUTATION_BUS, useValue: new Subject<MutationEvent>() },
  ],
};
