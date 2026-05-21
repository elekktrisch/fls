import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';

import { authGuard } from './session.guard';
import { SessionStore } from './session.store';

/**
 * Composes {@link authGuard} (authenticated + non-public routes) with a
 * SYSTEM_ADMINISTRATOR role check. Non-sysadmins land back at the home
 * route — the admin surfaces are entirely hidden from them. Matches the
 * server-side {@code @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")} gate.
 */
export const sysadminGuard: CanActivateFn = (route, state) => {
  const authResult = authGuard(route, state);
  if (authResult !== true) {
    return authResult;
  }
  const session = inject(SessionStore);
  if (session.isSystemAdmin()) {
    return true;
  }
  const router = inject(Router);
  return router.parseUrl('/');
};
