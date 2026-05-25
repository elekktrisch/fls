import { Routes } from '@angular/router';

import { authGuard } from '@core/session/session.guard';

/**
 * Authenticated landing page (S-165 home dashboard, pilot variant).
 * Available to every role — `tenantRequiredGuard` redirects here when the
 * session has no managing tenant, replacing the previous `/clubs` bounce
 * which was confusing for non-sysadmin roles. Role-specific variants land
 * in S-166 (club-admin) + S-167 (sysadmin); the route stays a single entry
 * with role switching inside the page.
 */
export const START_ROUTES: Routes = [
  {
    path: '',
    canActivate: [authGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./start.page').then((m) => m.StartPage),
  },
];
