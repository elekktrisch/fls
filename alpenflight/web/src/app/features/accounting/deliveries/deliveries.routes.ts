import { Routes } from '@angular/router';

import { tenantRequiredGuard } from '@core/session/tenant-required.guard';

export const DELIVERIES_ROUTES: Routes = [
  {
    path: '',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./list/deliveries-list.page').then((m) => m.DeliveriesListPage),
  },
  {
    path: ':id',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./view/deliveries-view.page').then((m) => m.DeliveriesViewPage),
  },
];
