import { Routes } from '@angular/router';

import { tenantRequiredGuard } from '@core/session/tenant-required.guard';

export const REPORTING_ROUTES: Routes = [
  {
    path: '',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () =>
      import('./picker/flight-reports-picker.page').then((m) => m.FlightReportsPickerPage),
  },
  {
    path: 'custom/:category/:filter/edit',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () =>
      import('./edit/report-custom-builder.page').then((m) => m.ReportCustomBuilderPage),
  },
  {
    path: 'custom/:category/:filter/:mode',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./results/report-results.page').then((m) => m.ReportResultsPage),
  },
  {
    path: ':category/:type',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./results/report-results.page').then((m) => m.ReportResultsPage),
  },
];
