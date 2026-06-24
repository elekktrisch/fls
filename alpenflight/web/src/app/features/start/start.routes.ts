import { Routes } from '@angular/router';

import { tenantRequiredGuard } from '@core/session/tenant-required.guard';

/**
 * Authenticated landing page (home dashboard). The route is a single entry
 * with role switching inside the shell (J-3 T-07): the shell picks the pilot
 * (S-165) / club-admin (S-166) / sysadmin (S-167) variant off the session
 * roles. A pilot-view toggle lets an admin fall back to the pilot dashboard.
 *
 * `tenantRequiredGuard` admits a tenant-bearing session and a club-less
 * SYSTEM_ADMINISTRATOR; an onboarding pilot with no `t_user` is redirected
 * into the join flow (`/join/pending` when a live request is waiting, else
 * `/join`) — the SPA-side gate that backs up the server-side JIT 403 (S-179).
 */
export const START_ROUTES: Routes = [
  {
    path: '',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./start-shell.page').then((m) => m.StartShellPage),
  },
];
