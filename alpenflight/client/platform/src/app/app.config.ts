import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { provideNzI18n, en_US } from 'ng-zorro-antd/i18n';
import { provideNzDateFnsAdapter } from 'ng-zorro-antd/core/time';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(),
    provideRouter(routes),
    // NzI18nService/NzDateAdapter are providedIn: 'root', so ng-zorro-antd requires these at the
    // app root regardless of which route uses a component from the library — the /records route's
    // SearchField (NzInputModule) and the /dev/component-spike route (story 1.5 deferred spike)
    // both do today. Inert everywhere else.
    provideNzI18n(en_US),
    provideNzDateFnsAdapter(),
  ],
};
