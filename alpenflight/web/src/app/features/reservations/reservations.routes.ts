import { Routes } from '@angular/router';

import { tenantRequiredGuard } from '@core/session/tenant-required.guard';

export const RESERVATIONS_ROUTES: Routes = [
  {
    path: '',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () =>
      import('./calendar/reservations-calendar.page').then((m) => m.ReservationsCalendarPage),
  },
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

export const RESERVATION_SCHEDULER_ROUTES: Routes = [
  {
    path: '',
    pathMatch: 'full',
    redirectTo: '/reservations',
  },
];
