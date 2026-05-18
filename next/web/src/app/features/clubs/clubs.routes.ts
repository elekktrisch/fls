import { Routes } from '@angular/router';

import { authGuard } from '@core/session/session.guard';

export const CLUBS_ROUTES: Routes = [
  {
    path: '',
    canActivate: [authGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./list/clubs-list.page').then((m) => m.ClubsListPage),
  },
  {
    path: 'new',
    canActivate: [authGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/clubs-edit.page').then((m) => m.ClubsEditPage),
  },
  {
    path: ':id/edit',
    canActivate: [authGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/clubs-edit.page').then((m) => m.ClubsEditPage),
  },
];
