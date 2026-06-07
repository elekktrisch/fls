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
  // `new/edit` + `:id/edit` + `:id/view` — the create/edit/view page (J-6 T-08).
  // The `:mode` segment gates editable-vs-read-only; create has no `:id`.
  {
    path: 'new/:mode',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/planning-edit.page').then((m) => m.PlanningEditPage),
  },
  {
    path: ':id/:mode',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/planning-edit.page').then((m) => m.PlanningEditPage),
  },
];
