import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';

import { authGuard } from './session.guard';
import { SessionStore } from './session.store';

/**
 * Composes {@link authGuard} with a "must have a managing tenant" gate.
 * Tenant-scoped pages (Aircraft, Locations, Persons, Flights, future
 * Reservations / Members) render empty under {@code @TenantId} filtering
 * when the principal has no {@code clubId}; in that case redirect to
 * {@code /start} — the authenticated landing every role can reach.
 *
 * <p>Production SYSTEM_ADMINISTRATOR has no {@code clubId} claim → bounce.
 * Non-sysadmins carry a {@code clubId} → pass through. A sysadmin who has
 * picked a tenant (impersonation flow, or the mock-auth principal that
 * ships with a fixed {@code MOCK_CLUB_ID}) ALSO passes through — the
 * criterion is "does this session have a tenant to operate on", not the
 * role flag.
 */
export const tenantRequiredGuard: CanActivateFn = (route, state) => {
  const authResult = authGuard(route, state);
  if (authResult !== true) {
    return authResult;
  }
  const session = inject(SessionStore);
  if (session.currentClubId() === null) {
    return inject(Router).parseUrl('/start');
  }
  return true;
};
