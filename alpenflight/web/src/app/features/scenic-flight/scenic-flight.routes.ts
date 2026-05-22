import { Routes } from '@angular/router';

export const SCENIC_FLIGHT_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./scenic-flight.component').then((m) => m.ScenicFlightComponent),
    data: { showNavBar: false, publicAccess: true },
  },
];
