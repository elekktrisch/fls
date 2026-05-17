import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';

import { SessionStore } from './session.store';

/**
 * Default-deny route guard. Public routes opt-in via `data.publicAccess === true`.
 *
 * Returns `false` (defer) while the session is still resolving (`idle` /
 * `loading`) so a hard refresh does not redirect to /login mid-init. S-021
 * inverts the body when real OIDC lands; the default-deny shape stays.
 */
export const authGuard: CanActivateFn = (route) => {
  const session = inject(SessionStore);
  const router = inject(Router);

  if (route.data['publicAccess'] === true) {
    return true;
  }
  if (session.isLoadingSession()) {
    return false;
  }
  return session.isAuthenticated() ? true : router.createUrlTree(['/login']);
};
