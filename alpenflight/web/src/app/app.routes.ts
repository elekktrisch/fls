import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadChildren: () => import('@features/landing/landing.routes').then((m) => m.LANDING_ROUTES),
  },
  {
    path: 'discovery-flight',
    loadChildren: () =>
      import('@features/discovery-flight/discovery-flight.routes').then(
        (m) => m.DISCOVERY_FLIGHT_ROUTES,
      ),
  },
  {
    path: 'scenic-flight',
    loadChildren: () =>
      import('@features/scenic-flight/scenic-flight.routes').then((m) => m.SCENIC_FLIGHT_ROUTES),
  },
  {
    path: 'auth',
    loadChildren: () => import('./core/auth/auth.routes').then((m) => m.AUTH_ROUTES),
  },
  {
    path: 'clubs',
    loadChildren: () => import('@features/clubs/clubs.routes').then((m) => m.CLUBS_ROUTES),
  },
  {
    path: 'locations',
    loadChildren: () =>
      import('@features/locations/locations.routes').then((m) => m.LOCATIONS_ROUTES),
  },
  {
    path: 'aircraft',
    loadChildren: () => import('@features/aircraft/aircraft.routes').then((m) => m.AIRCRAFT_ROUTES),
  },
  {
    path: 'flight-types',
    loadChildren: () =>
      import('@features/flight-types/flight-types.routes').then((m) => m.FLIGHT_TYPES_ROUTES),
  },
  {
    path: 'persons',
    loadChildren: () => import('@features/persons/persons.routes').then((m) => m.PERSONS_ROUTES),
  },
  {
    path: 'articles',
    loadChildren: () => import('@features/articles/articles.routes').then((m) => m.ARTICLES_ROUTES),
  },
  {
    path: 'dev/primitives',
    loadChildren: () =>
      import('./dev/primitives/primitives.routes').then((m) => m.PRIMITIVES_ROUTES),
  },
  { path: '**', redirectTo: '' },
];
