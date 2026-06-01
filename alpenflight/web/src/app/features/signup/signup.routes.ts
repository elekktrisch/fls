import { Routes } from '@angular/router';

export const SIGNUP_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./signup.component').then((m) => m.SignupComponent),
    data: { showNavBar: false, publicAccess: true },
  },
];

// /migrate/* moved to features/migrate-handshake/ at S-140; the
// migrate-start.component.ts placeholder was promoted into the handshake page.
