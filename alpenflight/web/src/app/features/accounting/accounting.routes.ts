import { Routes } from '@angular/router';

import { tenantRequiredGuard } from '@core/session/tenant-required.guard';

export const ACCOUNTING_ROUTES: Routes = [
  {
    path: '',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./list/accounting-list.page').then((m) => m.AccountingListPage),
  },
  {
    // T-12 fills these with the real edit form (filter-type-driven conditional
    // sections + the J-6b liveFieldErrors bar). Until then a placeholder
    // component resolves the route so the list's "new" button + row navigation
    // land somewhere.
    path: 'new',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () =>
      import('./edit/accounting-edit-placeholder.page').then(
        (m) => m.AccountingEditPlaceholderPage,
      ),
  },
  {
    path: ':id/edit',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () =>
      import('./edit/accounting-edit-placeholder.page').then(
        (m) => m.AccountingEditPlaceholderPage,
      ),
  },
];
