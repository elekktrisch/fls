import { Routes } from '@angular/router';

export const DEMO_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./demo-stub.component').then((m) => m.DemoStubComponent),
    data: { showNavBar: false, publicAccess: true },
  },
];
