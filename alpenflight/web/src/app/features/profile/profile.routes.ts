import { Routes } from '@angular/router';

import { authGuard } from '@core/session/session.guard';

export const PROFILE_ROUTES: Routes = [
  {
    path: '',
    canActivate: [authGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./profile-shell.page').then((m) => m.ProfileShellPage),
  },
];
