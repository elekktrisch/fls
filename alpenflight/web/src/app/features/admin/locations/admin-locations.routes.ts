import { Routes } from '@angular/router';

import { sysadminGuard } from '@core/session/sysadmin.guard';

export const ADMIN_LOCATIONS_ROUTES: Routes = [
  {
    path: '',
    canActivate: [sysadminGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./admin-locations.page').then((m) => m.AdminLocationsPage),
  },
  {
    path: ':clubId/new',
    canActivate: [sysadminGuard],
    data: { showNavBar: true },
    loadComponent: () =>
      import('./admin-locations-edit.page').then((m) => m.AdminLocationsEditPage),
  },
  {
    path: ':clubId/:id/edit',
    canActivate: [sysadminGuard],
    data: { showNavBar: true },
    loadComponent: () =>
      import('./admin-locations-edit.page').then((m) => m.AdminLocationsEditPage),
  },
];
