import { Routes } from '@angular/router';

import { tenantRequiredGuard } from '@core/session/tenant-required.guard';

export const DELIVERY_CREATION_TESTS_ROUTES: Routes = [
  {
    path: '',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () =>
      import('./list/delivery-creation-tests-list.page').then(
        (m) => m.DeliveryCreationTestsListPage,
      ),
  },
  {
    path: 'new',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () =>
      import('./edit/delivery-creation-tests-edit.page').then(
        (m) => m.DeliveryCreationTestsEditPage,
      ),
  },
  {
    path: ':id/edit',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () =>
      import('./edit/delivery-creation-tests-edit.page').then(
        (m) => m.DeliveryCreationTestsEditPage,
      ),
  },
];
