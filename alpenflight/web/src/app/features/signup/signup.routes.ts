import { Routes } from '@angular/router';

import { authGuard } from '../../core/session/session.guard';

export const SIGNUP_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./signup.component').then((m) => m.SignupComponent),
    data: { showNavBar: false, publicAccess: true },
  },
];

// /migrate/* is a temporary home inside features/signup/ until S-141 lands
// the JAR-download / upload wizard in its own features/migrate/ folder.
export const MIGRATE_ROUTES: Routes = [
  {
    path: 'start',
    loadComponent: () => import('./migrate-start.component').then((m) => m.MigrateStartComponent),
    canActivate: [authGuard],
    data: { showNavBar: false },
  },
];
