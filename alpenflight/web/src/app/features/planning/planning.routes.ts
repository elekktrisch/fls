import { Routes } from '@angular/router';

import { tenantRequiredGuard } from '@core/session/tenant-required.guard';

export const PLANNING_ROUTES: Routes = [
  {
    // `/planning` — the paged future-days list (J-6 T-07).
    path: '',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./list/planning-list.page').then((m) => m.PlanningListPage),
  },
  // `new/edit` + `:id/edit` + `:id/view` are owned by the edit page (T-08).
  // Declared here so the list page's New / edit / view links already resolve to
  // the contract the route table carries; until T-08 lands they 404-fallback in
  // the app. The list page is the T-07 deliverable.
];
