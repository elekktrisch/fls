import { Routes } from '@angular/router';

export const CONFIRM_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./confirm.component').then((m) => m.ConfirmComponent),
    data: { showNavBar: false, publicAccess: true },
  },
];
