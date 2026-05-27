import { Routes } from '@angular/router';

import { clubAdminGuard } from '@core/session/club-admin.guard';

export const USERS_ROUTES: Routes = [
  {
    path: '',
    canActivate: [clubAdminGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./list/users-list.page').then((m) => m.UsersListPage),
  },
  {
    path: 'new',
    canActivate: [clubAdminGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/users-edit.page').then((m) => m.UsersEditPage),
  },
  {
    path: ':id/edit',
    canActivate: [clubAdminGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/users-edit.page').then((m) => m.UsersEditPage),
  },
];
