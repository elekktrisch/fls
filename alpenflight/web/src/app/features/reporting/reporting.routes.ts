import { Routes } from '@angular/router';

import { tenantRequiredGuard } from '@core/session/tenant-required.guard';

/**
 * Reporting feature routes. Mirrors the legacy `/flightreports` URL shape
 * (`flsweb/src/reporting/FlightReportsModule.js`):
 *
 *   /flightreports                              → picker (T-09, built here)
 *   /flightreports/:category/:type              → canned results (T-10 fills)
 *   /flightreports/custom/:category/:filter/edit→ custom builder edit (T-11 fills)
 *   /flightreports/custom/:category/:filter/:mode → custom builder apply/view (T-11)
 *
 * The `custom/...` routes are listed BEFORE `:category/:type` so the literal
 * `custom` segment is not swallowed by the `:category` param. The results +
 * custom shells render the scaffold placeholder until T-10/T-11 land.
 */
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
    loadComponent: () => import('./report-placeholder.page').then((m) => m.ReportPlaceholderPage),
  },
  {
    path: 'custom/:category/:filter/:mode',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./report-placeholder.page').then((m) => m.ReportPlaceholderPage),
  },
  {
    path: ':category/:type',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./report-placeholder.page').then((m) => m.ReportPlaceholderPage),
  },
];
