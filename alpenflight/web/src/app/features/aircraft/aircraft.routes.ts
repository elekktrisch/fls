import { Routes } from '@angular/router';

import { tenantRequiredGuard } from '@core/session/tenant-required.guard';

export const AIRCRAFT_ROUTES: Routes = [
  {
    path: '',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./list/aircraft-list.page').then((m) => m.AircraftListPage),
  },
  {
    path: 'new',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/aircraft-edit.page').then((m) => m.AircraftEditPage),
  },
  {
    path: ':id/edit',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/aircraft-edit.page').then((m) => m.AircraftEditPage),
  },
];
