import { Routes } from '@angular/router';

export const DISCOVERY_FLIGHT_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./discovery-flight.component').then((m) => m.DiscoveryFlightComponent),
    data: { showNavBar: false, publicAccess: true },
  },
];
