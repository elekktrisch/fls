import { Routes } from '@angular/router';

import { authGuard } from '@core/session/session.guard';

import { JoinStore } from './join.store';

export const JOIN_ROUTES: Routes = [
  {
    path: '',
    canActivate: [authGuard],
    providers: [JoinStore],
    data: { showNavBar: false },
    children: [
      {
        path: '',
        loadComponent: () => import('./join.page').then((m) => m.JoinPageComponent),
      },
      {
        path: 'pending',
        loadComponent: () => import('./join-pending.page').then((m) => m.JoinPendingPageComponent),
      },
    ],
  },
];
