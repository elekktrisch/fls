import { Routes } from '@angular/router';

import { tenantRequiredGuard } from '@core/session/tenant-required.guard';

/**
 * `/airmovements` — motor "air movements" (S-064). The SAME list + wizard the
 * `/flights` route family renders, parameterized via `data.flightVariant: 'motor'`:
 * the list pre-filters to MOTOR flights and the wizard creates MOTOR flights
 * with the tow step suppressed. Legacy `flsweb/` had a near-duplicate
 * `flights/airmovements/` AngularJS module; the rewrite does NOT duplicate —
 * one parameterized list/form, two routes. See `flight-variant.ts`.
 */
export const AIRMOVEMENTS_ROUTES: Routes = [
  {
    path: '',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true, flightVariant: 'motor' },
    loadComponent: () => import('./list/flights-list.page').then((m) => m.FlightsListPage),
  },
  {
    path: 'new',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true, flightVariant: 'motor' },
    loadComponent: () => import('./edit/flights-edit.page').then((m) => m.FlightsEditPage),
  },
  {
    path: 'copy/:id',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true, flightVariant: 'motor' },
    loadComponent: () => import('./edit/flights-edit.page').then((m) => m.FlightsEditPage),
  },
  {
    // `:id/edit` (not `:id`) — mirrors the flights route family (a read-only
    // `/airmovements/:id` detail view is reserved for later).
    path: ':id/edit',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true, flightVariant: 'motor' },
    loadComponent: () => import('./edit/flights-edit.page').then((m) => m.FlightsEditPage),
  },
];
