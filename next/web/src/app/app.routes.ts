import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadChildren: () => import('@features/landing/landing.routes').then((m) => m.LANDING_ROUTES),
  },
  {
    path: 'hello',
    loadChildren: () => import('@features/hello/hello.routes').then((m) => m.HELLO_ROUTES),
  },
  {
    path: 'dev/primitives',
    loadChildren: () =>
      import('./dev/primitives/primitives.routes').then((m) => m.PRIMITIVES_ROUTES),
  },
  { path: '**', redirectTo: '' },
];
