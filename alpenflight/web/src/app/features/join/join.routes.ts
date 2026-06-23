import { Routes } from '@angular/router';

import { authGuard } from '@core/session/session.guard';

import { JoinStore } from './join.store';

/**
 * Pilot self-serve club-join routes. Authenticated but deliberately NOT
 * tenant-guarded — the pilot has no `t_user`/tenant yet (that's the whole
 * point of joining). `/join/pending` + the `/start` redirect are T-11.
 *
 * `JoinStore` is provided here, not at the component, so `/join` and the future
 * `/join/pending` share one store instance across the flow.
 */
export const JOIN_ROUTES: Routes = [
  {
    path: '',
    canActivate: [authGuard],
    providers: [JoinStore],
    data: { showNavBar: false },
    loadComponent: () => import('./join.page').then((m) => m.JoinPageComponent),
  },
];
