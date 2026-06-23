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
    // Calendar view of the reservation data (J-5 T-10) — a top-level route the
    // scheduler spec navigates to (`/reservation-scheduler`), served lazily by
    // the reservations feature folder.
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
    // `/planningsetup` — the bulk-setup wizard (J-6 T-09), a top-level route
    // (matches the list-page Setup button + legacy), served by the planning
    // feature folder.
    path: 'planningsetup',
    loadChildren: () =>
      import('@features/planning/planning.routes').then((m) => m.PLANNING_SETUP_ROUTES),
  },
  {
    // Motor flights ("air movements") are unified into this same /flights list
    // (a Flight with a motor aircraft + no tow) — legacy's separate
    // /airmovements screen is NOT carried forward (J-2 T-36 / S-064).
    path: 'flights',
    loadChildren: () => import('@features/flights/flights.routes').then((m) => m.FLIGHTS_ROUTES),
  },
  {
    // `/flightreports` — canned + custom flight reports (J-7), replacing the
    // legacy flsweb/src/reporting/ FlightReportsModule. Served by the reporting
    // feature folder.
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
    // `/accountingrules` — the AccountingRuleFilter config screen (J-8),
    // replacing legacy masterdata/accountingRules/. CLUB_ADMINISTRATOR-gated on
    // the server (all CRUD endpoints); the nav entry lives under
    // CLUB_ADMIN_SECTIONS (nav-sections.ts).
    path: 'accountingrules',
    loadChildren: () =>
      import('@features/accounting/accounting.routes').then((m) => m.ACCOUNTING_ROUTES),
  },
  {
    // `/deliverycreationtests` — the rules-engine test harness (J-9), replacing
    // legacy masterdata/deliveryCreationTests/. CLUB_ADMINISTRATOR-gated on the
    // server; the nav entry lives under the Masterdata group (nav-sections.ts).
    path: 'deliverycreationtests',
    loadChildren: () =>
      import('@features/accounting/delivery-creation-tests/delivery-creation-tests.routes').then(
        (m) => m.DELIVERY_CREATION_TESTS_ROUTES,
      ),
  },
  {
    // `/deliveries` — the read-only invoice-draft viewer (list + view), replacing
    // legacy masterdata/deliveries/ (read path). The nav entry lives under the
    // Masterdata group (nav-sections.ts).
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
