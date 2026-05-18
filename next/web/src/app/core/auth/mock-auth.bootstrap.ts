// S-048: DELETE WITH PARENT DIRECTORY when S-019/S-020 land. See README.md.

import { inject } from '@angular/core';

import { SessionStore, type User } from '../session/session.store';

const MOCK_CLUB_ID = '019e30c3-2c00-7001-8000-000000000001';

export const MOCK_USER: User = {
  id: 'mock-sysadmin',
  username: 'mock-sysadmin',
  email: 'mock@local',
  firstName: 'Mock',
  lastName: 'Sysadmin',
  clubId: MOCK_CLUB_ID,
  roles: ['SYSTEM_ADMINISTRATOR'],
};

/**
 * `provideAppInitializer` factory: stamps the mock principal into
 * SessionStore before any route navigation runs, so {@link
 * import('../session/session.guard').authGuard authGuard} sees an
 * authenticated user on first paint.
 */
export function mockAuthBootstrap(): void {
  inject(SessionStore).login(MOCK_USER, MOCK_CLUB_ID);
}
