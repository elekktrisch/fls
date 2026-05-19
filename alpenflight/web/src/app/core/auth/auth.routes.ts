import type { Routes } from '@angular/router';

export const AUTH_ROUTES: Routes = [
  {
    path: 'callback',
    loadComponent: () => import('./auth-callback.page').then((m) => m.AuthCallbackPage),
    data: { showNavBar: false, publicAccess: true },
  },
  {
    path: 'logout',
    loadComponent: () => import('./logout.page').then((m) => m.LogoutPage),
    data: { showNavBar: false, publicAccess: true },
  },
];
