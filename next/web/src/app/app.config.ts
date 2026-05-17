import { provideHttpClient, withFetch } from '@angular/common/http';
import { ApplicationConfig, provideZonelessChangeDetection } from '@angular/core';
import { provideRouter, withComponentInputBinding, withViewTransitions } from '@angular/router';
import { Subject } from 'rxjs';

import { routes } from './app.routes';
import { MUTATION_BUS, type MutationEvent } from './core/mutation-bus/mutation-bus';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    provideHttpClient(withFetch()),
    provideRouter(routes, withComponentInputBinding(), withViewTransitions()),
    { provide: MUTATION_BUS, useValue: new Subject<MutationEvent>() },
  ],
};
