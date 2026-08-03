import { inject } from '@angular/core';
import { type PartialMatchRouteSnapshot, Router, type Routes, type UrlTree } from '@angular/router';

/**
 * A public registration URL with no club has no club to register with, so it
 * returns the visitor to the front page — legacy's `$location.path("/main")`
 * (`TryFlightController.js:8-10`).
 *
 * A function rather than a string `redirectTo`: a string redirect keeps only the
 * query params written into it, which would drop the `?lang=` a visitor arrived
 * with.
 */
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
