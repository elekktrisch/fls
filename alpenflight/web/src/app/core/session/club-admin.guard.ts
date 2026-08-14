import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';

import { composeAfterAuth, resolveAuth } from './session.guard';
import { SessionStore } from './session.store';

export const clubAdminGuard: CanActivateFn = (route, state) => {
  const session = inject(SessionStore);
  const router = inject(Router);
  return composeAfterAuth(resolveAuth(route, state), () =>
    session.currentClubId() === null || !session.isClubAdmin() ? router.parseUrl('/start') : true,
  );
};
