import { Routes } from '@angular/router';

import { authGuard } from '@core/session/session.guard';

/**
 * Authenticated landing page. Available to every role (no tenant required,
 * no role gate beyond auth) — `tenantRequiredGuard` redirects here when the
 * session has no managing tenant, replacing the previous `/clubs` bounce
 * which was confusing for non-sysadmin roles.
 *
 * The dummy implementation lands here as a placeholder; a real home /
 * dashboard surface follows in a future story.
 */
export const START_ROUTES: Routes = [
  {
    path: '',
    canActivate: [authGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./start.page').then((m) => m.StartPage),
  },
];
