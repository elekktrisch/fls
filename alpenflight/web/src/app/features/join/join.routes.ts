import { Routes } from '@angular/router';

import { authGuard } from '@core/session/session.guard';

import { JoinStore } from './join.store';

/**
 * Pilot self-serve club-join routes. Authenticated but deliberately NOT
 * tenant-guarded — the pilot has no `t_user`/tenant yet (that's the whole
 * point of joining).
 *
 * `JoinStore` is provided on the parent so `/join` and `/join/pending` share
 * one store instance across the flow (a submit on `/join` holds the request the
 * pending page then renders without a re-fetch race).
 */
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
