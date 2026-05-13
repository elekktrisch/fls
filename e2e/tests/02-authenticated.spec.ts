import { test, gotoRoute, screenshot } from '../fixtures';

const FLIGHT_ID = '728a5199-3e1e-43a6-970a-c3cd741884ff'; // seeded "PAX flight"

const AUTH_ROUTES: { name: string; path: string }[] = [
  { name: 'dashboard',                        path: '/main' },
  { name: 'flights-list',                     path: '/flights' },
  { name: 'flights-edit',                     path: `/flights/${FLIGHT_ID}` },
  { name: 'airmovements-list',                path: '/airmovements' },
  { name: 'planning-list',                    path: '/planning' },
  { name: 'planning-setup',                   path: '/planningsetup' },
  { name: 'reservations-list',                path: '/reservations' },
  { name: 'reservation-scheduler',            path: '/reservation-scheduler' },
  { name: 'flight-reports',                   path: '/flightreports' },
  { name: 'profile',                          path: '/profile' },
  { name: 'system-logs',                      path: '/system/logs' },
  { name: 'masterdata-aircrafts',             path: '/masterdata/aircrafts' },
  { name: 'masterdata-persons',               path: '/masterdata/persons' },
  { name: 'masterdata-users',                 path: '/masterdata/users' },
  { name: 'masterdata-clubs',                 path: '/masterdata/clubs' },
  { name: 'masterdata-locations',             path: '/masterdata/locations' },
  { name: 'masterdata-flighttypes',           path: '/masterdata/flightTypes' },
  { name: 'masterdata-accountingrules',       path: '/masterdata/accountingRuleFilters' },
  { name: 'masterdata-deliveries',            path: '/masterdata/deliveries' },
  { name: 'masterdata-deliverycreationtests', path: '/masterdata/deliveryCreationTests' },
  { name: 'masterdata-memberstates',          path: '/masterdata/memberStates' },
  { name: 'masterdata-personcategories',      path: '/masterdata/personCategories' },
];

for (const { name, path } of AUTH_ROUTES) {
  test(`auth:${name}`, async ({ loggedInPage }) => {
    await gotoRoute(loggedInPage, path);
    await screenshot(loggedInPage, `auth-${name}`);
  });
}
