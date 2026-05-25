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
    loadComponent: () =>
      import('./placeholder/flights-edit-placeholder.page').then(
        (m) => m.FlightsEditPlaceholderPage,
      ),
  },
  {
    path: 'copy/:id',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () =>
      import('./placeholder/flights-edit-placeholder.page').then(
        (m) => m.FlightsEditPlaceholderPage,
      ),
  },
  {
    path: ':id/edit',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () =>
      import('./placeholder/flights-edit-placeholder.page').then(
        (m) => m.FlightsEditPlaceholderPage,
      ),
  },
];
