import { Routes } from '@angular/router';

export const SIGNUP_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./signup.component').then((m) => m.SignupComponent),
    data: { showNavBar: false, publicAccess: true },
  },
];

export const MIGRATE_ROUTES: Routes = [
  // /migrate/start placeholder — S-141 replaces with the real JAR-download flow.
  // authGuard is the route-level default for non-public routes (see app.routes.ts +
  // alpenflight/web/CLAUDE.md §2); not adding `publicAccess: true` keeps the
  // unverified-user gate in front of this landing.
  {
    path: 'start',
    loadComponent: () =>
      import('./post-signup-landing.component').then((m) => m.MigrateStartComponent),
    data: { showNavBar: false },
  },
];
