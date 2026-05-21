import { Routes } from '@angular/router';

import { sysadminGuard } from '@core/session/sysadmin.guard';

export const ADMIN_LOCATIONS_ROUTES: Routes = [
  {
    path: '',
    canActivate: [sysadminGuard],
    data: { showNavBar: true },
    loadComponent: () =>
      import('./admin-locations.page').then((m) => m.AdminLocationsPage),
  },
];
