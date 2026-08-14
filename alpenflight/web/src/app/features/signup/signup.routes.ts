import { Routes } from '@angular/router';

export const SIGNUP_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./signup.component').then((m) => m.SignupComponent),
    data: { showNavBar: false, publicAccess: true },
  },
];
