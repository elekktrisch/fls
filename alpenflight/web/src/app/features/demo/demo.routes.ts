import { Routes } from '@angular/router';

export const DEMO_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./demo.page').then((m) => m.DemoPage),
    data: { showNavBar: false, publicAccess: true },
  },
];
