import { Routes } from '@angular/router';

import { clubAdminGuard } from '@core/session/club-admin.guard';

export const JOIN_REQUESTS_ROUTES: Routes = [
  {
    path: '',
    canActivate: [clubAdminGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./join-requests-list.page').then((m) => m.JoinRequestsListPage),
  },
];
