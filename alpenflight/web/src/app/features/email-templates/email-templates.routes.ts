import { Routes } from '@angular/router';

import { tenantRequiredGuard } from '@core/session/tenant-required.guard';

export const EMAIL_TEMPLATES_ROUTES: Routes = [
  {
    path: '',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () =>
      import('./list/email-templates-list.page').then((m) => m.EmailTemplatesListPage),
  },
  {
    path: ':templateKey/:languageLocale',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () =>
      import('./edit/email-templates-edit.page').then((m) => m.EmailTemplatesEditPage),
  },
];
