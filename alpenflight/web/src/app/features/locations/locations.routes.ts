import { Routes } from '@angular/router';

import { tenantRequiredGuard } from '@core/session/tenant-required.guard';

export const LOCATIONS_ROUTES: Routes = [
  {
    path: '',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./list/locations-list.page').then((m) => m.LocationsListPage),
  },
  {
    path: 'new',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/locations-edit.page').then((m) => m.LocationsEditPage),
  },
  {
    path: ':id/edit',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/locations-edit.page').then((m) => m.LocationsEditPage),
  },
];
