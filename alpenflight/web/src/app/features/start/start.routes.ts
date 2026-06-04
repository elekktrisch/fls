import { Routes } from '@angular/router';

import { authGuard } from '@core/session/session.guard';

/**
 * Authenticated landing page (home dashboard). Available to every role —
 * `tenantRequiredGuard` redirects here when the session has no managing tenant,
 * replacing the previous `/clubs` bounce which was confusing for non-sysadmin
 * roles. The route is a single entry with role switching inside the shell
 * (J-3 T-07): the shell picks the pilot (S-165) / club-admin (S-166) / sysadmin
 * (S-167) variant off the session roles. A pilot-view toggle lets an admin fall
 * back to the pilot dashboard.
 */
export const START_ROUTES: Routes = [
  {
    path: '',
    canActivate: [authGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./start-shell.page').then((m) => m.StartShellPage),
  },
];
