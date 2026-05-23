import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';

import { authGuard } from './session.guard';
import { SessionStore } from './session.store';

/**
 * Composes {@link authGuard} with a "must have a managing tenant" gate.
 * SYSTEM_ADMINISTRATOR has no {@code clubId} claim — tenant-scoped pages
 * (Aircraft, Locations, future Flights / Reservations / Members) render
 * empty under {@code @TenantId} filtering with no useful action. Per the
 * S-159 nav strip, the sysadmin shell hides the tenant entries; this guard
 * is the deep-link / bookmark / back-button counterpart: it redirects
 * sysadmin to {@code /clubs}, the cross-cutting surface they can act on.
 *
 * <p>Non-sysadmins (CLUB_ADMINISTRATOR, FLIGHT_OPERATOR, PILOT,
 * OFFICE_USER, GUEST) pass through unchanged — their JWT carries a
 * {@code clubId} that {@code ClubTenantIdentifierResolver} resolves to a
 * non-{@code NO_TENANT} tenant.
 */
export const tenantRequiredGuard: CanActivateFn = (route, state) => {
  const authResult = authGuard(route, state);
  if (authResult !== true) {
    return authResult;
  }
  const session = inject(SessionStore);
  if (session.isSystemAdmin()) {
    return inject(Router).parseUrl('/clubs');
  }
  return true;
};
