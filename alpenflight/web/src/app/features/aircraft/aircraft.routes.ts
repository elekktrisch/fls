import { Routes } from '@angular/router';

import { authGuard } from '@core/session/session.guard';

export const AIRCRAFT_ROUTES: Routes = [
  {
    path: '',
    canActivate: [authGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./list/aircraft-list.page').then((m) => m.AircraftListPage),
  },
  {
    path: 'new',
    canActivate: [authGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/aircraft-edit.page').then((m) => m.AircraftEditPage),
  },
  {
    path: ':id/edit',
    canActivate: [authGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/aircraft-edit.page').then((m) => m.AircraftEditPage),
  },
];
