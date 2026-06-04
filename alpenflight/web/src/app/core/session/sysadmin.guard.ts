import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';

import { composeAfterAuth, resolveAuth } from './session.guard';
import { SessionStore } from './session.store';

/**
 * Composes {@link resolveAuth} (authenticated + non-public routes) with a
 * SYSTEM_ADMINISTRATOR role check. Non-sysadmins land back at the home
 * route — the admin surfaces are entirely hidden from them. Matches the
 * server-side {@code @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")} gate.
 *
 * <p>The role check runs only after auth resolves (settled or post-settle),
 * so a mid-renew navigation waits rather than being cancelled.
 */
export const sysadminGuard: CanActivateFn = (route, state) => {
  const session = inject(SessionStore);
  const router = inject(Router);
  return composeAfterAuth(resolveAuth(route, state), () =>
    session.isSystemAdmin() ? true : router.parseUrl('/'),
  );
};
