import { Routes } from '@angular/router';

import { tenantRequiredGuard } from '@core/session/tenant-required.guard';

export const RESERVATIONS_ROUTES: Routes = [
  {
    path: '',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () =>
      import('./list/reservations-list.page').then((m) => m.ReservationsListPage),
  },
  // `new` + `:id/edit` are wired by T-09 (the edit form). The list page's
  // "New" button and kebab-edit navigate to these paths; until T-09 lands they
  // resolve to the edit-page placeholder built there. Declared here so the
  // route table already carries the contract the list page links to.
  {
    path: 'new',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/reservation-edit.page').then((m) => m.ReservationEditPage),
  },
  {
    path: ':id/edit',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/reservation-edit.page').then((m) => m.ReservationEditPage),
  },
];
