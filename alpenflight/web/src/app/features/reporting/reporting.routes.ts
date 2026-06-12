import { Routes } from '@angular/router';

import { tenantRequiredGuard } from '@core/session/tenant-required.guard';

/**
 * Reporting feature routes. Mirrors the legacy `/flightreports` URL shape
 * (`flsweb/src/reporting/FlightReportsModule.js`):
 *
 *   /flightreports                              → picker (T-09, built here)
 *   /flightreports/:category/:type              → canned results (T-10)
 *   /flightreports/custom/:category/:filter/edit→ custom builder edit (T-11 fills)
 *   /flightreports/custom/:category/:filter/:mode → custom builder apply/view (T-11)
 *
 * The `custom/...` routes are listed BEFORE `:category/:type` so the literal
 * `custom` segment is not swallowed by the `:category` param. The custom shells
 * still render the scaffold placeholder until T-11 lands.
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
    loadComponent: () =>
      import('./edit/report-custom-builder.page').then((m) => m.ReportCustomBuilderPage),
  },
  {
    // Custom-filter results (mode = apply | view): the builder encodes the filter
    // into `:filter` and navigates here; the results page decodes it and renders.
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
