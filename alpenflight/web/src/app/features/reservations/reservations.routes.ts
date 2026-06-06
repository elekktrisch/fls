import { Routes } from '@angular/router';

import { tenantRequiredGuard } from '@core/session/tenant-required.guard';

export const RESERVATIONS_ROUTES: Routes = [
  {
    // `/reservations` is now the calendar-first screen (J-5 T-39): day view
    // (the old `/reservation-scheduler` grid, folded in) + week view + a
    // day/week toggle + a week day-picker. The paged table is dropped as the
    // primary view; the calendar shares the same store data.
    path: '',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () =>
      import('./calendar/reservations-calendar.page').then((m) => m.ReservationsCalendarPage),
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

/**
 * `/reservation-scheduler` — kept as a working URL but folded into the calendar
 * (J-5 T-39): the day view of `/reservations` IS the old scheduler grid, so the
 * standalone route now redirects to `/reservations`. Registered top-level in
 * `app.routes.ts`; the redirect keeps any bookmark / legacy link alive.
 */
export const RESERVATION_SCHEDULER_ROUTES: Routes = [
  {
    path: '',
    pathMatch: 'full',
    redirectTo: '/reservations',
  },
];
