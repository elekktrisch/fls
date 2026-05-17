import { Routes } from '@angular/router';

export const PRIMITIVES_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./primitives-showcase.page'),
    data: { showNavBar: false, publicAccess: true },
  },
];
