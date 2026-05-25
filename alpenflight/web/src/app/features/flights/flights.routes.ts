import { Routes } from '@angular/router';

import { tenantRequiredGuard } from '@core/session/tenant-required.guard';

export const FLIGHTS_ROUTES: Routes = [
  {
    path: '',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./list/flights-list.page').then((m) => m.FlightsListPage),
  },
  {
    path: 'new',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/flights-edit.page').then((m) => m.FlightsEditPage),
  },
  {
    path: 'copy/:id',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/flights-edit.page').then((m) => m.FlightsEditPage),
  },
  {
    // `:id/edit` (not `:id`): keeps `/flights/:id` free for a read-only
    // detail view (S-105 / S-110). The legacy-parity oracle's `/flights/:id`
    // semantic maps to *this* edit route.
    path: ':id/edit',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/flights-edit.page').then((m) => m.FlightsEditPage),
  },
];
