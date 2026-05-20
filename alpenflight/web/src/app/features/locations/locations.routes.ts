import { Routes } from '@angular/router';

import { authGuard } from '@core/session/session.guard';

export const LOCATIONS_ROUTES: Routes = [
  {
    path: '',
    canActivate: [authGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./list/locations-list.page').then((m) => m.LocationsListPage),
  },
  {
    path: 'new',
    canActivate: [authGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/locations-edit.page').then((m) => m.LocationsEditPage),
  },
  {
    path: ':id/edit',
    canActivate: [authGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/locations-edit.page').then((m) => m.LocationsEditPage),
  },
];
