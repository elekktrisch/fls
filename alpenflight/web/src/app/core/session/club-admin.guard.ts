import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';

import { composeAfterAuth, resolveAuth } from './session.guard';
import { SessionStore } from './session.store';

/**
 * Composes {@link resolveAuth} (authenticated + non-public) with a "must be
 * a managing CLUB_ADMINISTRATOR of a tenant" gate. Mirrors the server-side
 * {@code @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")} on
 * {@code /api/v1/users/**}. Non-admins land back at {@code /start} so the
 * empty-shell flash + 403-on-list-load never reaches them.
 *
 * <p>The club-admin + tenant check runs only after auth resolves (settled or
 * post-settle), so a mid-renew navigation waits rather than being cancelled
 * and the {@code currentClubId()} read never sees the transient loading null.
 */
export const clubAdminGuard: CanActivateFn = (route, state) => {
  const session = inject(SessionStore);
  const router = inject(Router);
  return composeAfterAuth(resolveAuth(route, state), () =>
    session.currentClubId() === null || !session.isClubAdmin() ? router.parseUrl('/start') : true,
  );
};
