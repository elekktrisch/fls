import { inject } from '@angular/core';
import { type PartialMatchRouteSnapshot, Router, type Routes, type UrlTree } from '@angular/router';

function toLandingPage({ queryParams }: PartialMatchRouteSnapshot): UrlTree {
  return inject(Router).createUrlTree(['/'], { queryParams });
}

export const DISCOVERY_FLIGHT_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: toLandingPage },
  {
    path: ':clubSlug',
    loadComponent: () =>
      import('./discovery-flight.page').then((m) => m.DiscoveryFlightPageComponent),
    data: { showNavBar: false, publicAccess: true },
  },
];

export const SCENIC_FLIGHT_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: toLandingPage },
  {
    path: ':clubSlug',
    loadComponent: () => import('./scenic-flight.page').then((m) => m.ScenicFlightPageComponent),
    data: { showNavBar: false, publicAccess: true },
  },
];
