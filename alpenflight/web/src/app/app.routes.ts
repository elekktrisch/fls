import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadChildren: () => import('@features/landing/landing.routes').then((m) => m.LANDING_ROUTES),
  },
  {
    path: 'discovery-flight',
    loadChildren: () =>
      import('@features/public-registration/public-registration.routes').then(
        (m) => m.DISCOVERY_FLIGHT_ROUTES,
      ),
  },
  {
    path: 'scenic-flight',
    loadChildren: () =>
      import('@features/scenic-flight/scenic-flight.routes').then((m) => m.SCENIC_FLIGHT_ROUTES),
  },
  {
    path: 'demo',
    loadChildren: () => import('@features/demo/demo.routes').then((m) => m.DEMO_ROUTES),
  },
  {
    path: 'auth',
    loadChildren: () => import('./core/auth/auth.routes').then((m) => m.AUTH_ROUTES),
  },
  {
    path: 'signup',
    loadChildren: () => import('@features/signup/signup.routes').then((m) => m.SIGNUP_ROUTES),
  },
  {
    path: 'migrate',
    loadChildren: () =>
      import('@features/migrate-handshake/migrate-handshake.routes').then(
        (m) => m.MIGRATE_HANDSHAKE_ROUTES,
      ),
  },
  {
    path: 'join',
    loadChildren: () => import('@features/join/join.routes').then((m) => m.JOIN_ROUTES),
  },
  {
    path: 'start',
    loadChildren: () => import('@features/start/start.routes').then((m) => m.START_ROUTES),
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
    path: 'reservations',
    loadChildren: () =>
      import('@features/reservations/reservations.routes').then((m) => m.RESERVATIONS_ROUTES),
  },
  {
    path: 'reservation-scheduler',
    loadChildren: () =>
      import('@features/reservations/reservations.routes').then(
        (m) => m.RESERVATION_SCHEDULER_ROUTES,
      ),
  },
  {
    path: 'planning',
    loadChildren: () => import('@features/planning/planning.routes').then((m) => m.PLANNING_ROUTES),
  },
  {
    path: 'planningsetup',
    loadChildren: () =>
      import('@features/planning/planning.routes').then((m) => m.PLANNING_SETUP_ROUTES),
  },
  {
    path: 'flights',
    loadChildren: () => import('@features/flights/flights.routes').then((m) => m.FLIGHTS_ROUTES),
  },
  {
    path: 'flightreports',
    loadChildren: () =>
      import('@features/reporting/reporting.routes').then((m) => m.REPORTING_ROUTES),
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
    path: 'profile',
    loadChildren: () => import('@features/profile/profile.routes').then((m) => m.PROFILE_ROUTES),
  },
  {
    path: 'users',
    loadChildren: () => import('@features/users/users.routes').then((m) => m.USERS_ROUTES),
  },
  {
    path: 'system/logs',
    loadChildren: () =>
      import('@features/audit-logs/audit-logs.routes').then((m) => m.AUDIT_LOGS_ROUTES),
  },
  {
    path: 'system/jobs',
    loadChildren: () => import('@features/jobs/jobs.routes').then((m) => m.JOBS_ROUTES),
  },
  {
    path: 'join-requests',
    loadChildren: () =>
      import('@features/join-requests/join-requests.routes').then((m) => m.JOIN_REQUESTS_ROUTES),
  },
  {
    path: 'articles',
    loadChildren: () => import('@features/articles/articles.routes').then((m) => m.ARTICLES_ROUTES),
  },
  {
    path: 'email-templates',
    loadChildren: () =>
      import('@features/email-templates/email-templates.routes').then(
        (m) => m.EMAIL_TEMPLATES_ROUTES,
      ),
  },
  {
    path: 'accountingrules',
    loadChildren: () =>
      import('@features/accounting/accounting.routes').then((m) => m.ACCOUNTING_ROUTES),
  },
  {
    path: 'deliverycreationtests',
    loadChildren: () =>
      import('@features/accounting/delivery-creation-tests/delivery-creation-tests.routes').then(
        (m) => m.DELIVERY_CREATION_TESTS_ROUTES,
      ),
  },
  {
    path: 'deliveries',
    loadChildren: () =>
      import('@features/accounting/deliveries/deliveries.routes').then((m) => m.DELIVERIES_ROUTES),
  },
  {
    path: 'dev/primitives',
    loadChildren: () =>
      import('./dev/primitives/primitives.routes').then((m) => m.PRIMITIVES_ROUTES),
  },
  { path: '**', redirectTo: '' },
];
