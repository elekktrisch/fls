import { Routes } from '@angular/router';

export const LOSTPASSWORD_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./lostpassword.component').then((m) => m.LostpasswordComponent),
    data: { showNavBar: false, publicAccess: true },
  },
];
