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
    // The edit form: core fields + the filter-type-legacyId-driven conditional
    // sections (article-target / recipient-target / aircraft-filter /
    // no-landing-tax) + the J-6b liveFieldErrors bar (T-12). The match-list
    // predicate sub-component (T-13) mounts inside the page.
    path: 'new',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/accounting-edit.page').then((m) => m.AccountingEditPage),
  },
  {
    path: ':id/edit',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/accounting-edit.page').then((m) => m.AccountingEditPage),
  },
];
