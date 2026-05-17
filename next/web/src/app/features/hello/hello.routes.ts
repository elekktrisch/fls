import { Routes } from '@angular/router';

export const HELLO_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./hello.component').then((m) => m.HelloComponent),
    data: { showNavBar: false, publicAccess: true },
  },
];
