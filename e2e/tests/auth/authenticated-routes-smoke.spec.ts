import { test, gotoRoute, screenshot } from '../../fixtures';

const FLIGHT_ID = 'F1500005-0000-0000-0000-000000000001'; // deterministic historical fixture flight from _test-fixture.sql

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
];

for (const { name, path } of AUTH_ROUTES) {
  test(`auth:${name}`, async ({ loggedInPage }) => {
    await gotoRoute(loggedInPage, path);
    await screenshot(loggedInPage, name);
  });
}
