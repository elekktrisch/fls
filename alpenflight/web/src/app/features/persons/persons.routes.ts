import { Routes } from '@angular/router';

import { tenantRequiredGuard } from '@core/session/tenant-required.guard';

export const PERSONS_ROUTES: Routes = [
  {
    path: '',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./list/persons-list.page').then((m) => m.PersonsListPage),
  },
  {
    path: 'new',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/persons-edit.page').then((m) => m.PersonsEditPage),
  },
  {
    path: ':id/edit',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/persons-edit.page').then((m) => m.PersonsEditPage),
  },
];
