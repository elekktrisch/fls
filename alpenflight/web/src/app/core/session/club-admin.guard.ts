import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';

import { authGuard } from './session.guard';
import { SessionStore } from './session.store';

/**
 * Composes {@link authGuard} (authenticated + non-public) with a "must be
 * a managing CLUB_ADMINISTRATOR of a tenant" gate. Mirrors the server-side
 * {@code @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")} on
 * {@code /api/v1/users/**}. Non-admins land back at {@code /start} so the
 * empty-shell flash + 403-on-list-load never reaches them.
 */
export const clubAdminGuard: CanActivateFn = (route, state) => {
  const authResult = authGuard(route, state);
  if (authResult !== true) {
    return authResult;
  }
  const session = inject(SessionStore);
  const router = inject(Router);
  if (session.currentClubId() === null || !session.isClubAdmin()) {
    return router.parseUrl('/start');
  }
  return true;
};
