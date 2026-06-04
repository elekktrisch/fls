import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';

import { composeAfterAuth, resolveAuth } from './session.guard';
import { SessionStore } from './session.store';

/**
 * Composes {@link resolveAuth} with a "must have a managing tenant" gate.
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
 *
 * <p>The {@code currentClubId()} read is deferred behind {@link resolveAuth}
 * so that, during the transient session-loading window, it is evaluated only
 * AFTER the session settles (when {@code loadMe()} has populated the club id)
 * — never against the transiently-null loading state, which used to bounce a
 * mid-renew navigation to {@code /start}.
 */
export const tenantRequiredGuard: CanActivateFn = (route, state) => {
  const session = inject(SessionStore);
  const router = inject(Router);
  return composeAfterAuth(resolveAuth(route, state), () =>
    session.currentClubId() === null ? router.parseUrl('/start') : true,
  );
};
