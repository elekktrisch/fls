import { inject } from '@angular/core';
import { Router, type CanActivateFn, type UrlTree } from '@angular/router';
import { type Observable, map, of } from 'rxjs';

import { JoinRequestsService } from '@api/generated/join-requests/join-requests.service';
import type { JoinRequestResponse } from '@api/generated/model';

import { composeAfterAuth, resolveAuth } from './session.guard';
import { SessionStore } from './session.store';

/**
 * The onboarding redirect for a tenant-less non-admin principal: `/join/pending`
 * when a live (non-final) {@code JoinRequest} is waiting, else `/join` to enter
 * a code. Pure so it's covered without a TestBed (web CLAUDE.md §8).
 */
export function onboardingRedirect(request: JoinRequestResponse | null): '/join/pending' | '/join' {
  return request?.status === 'PENDING' ? '/join/pending' : '/join';
}

/**
 * Composes {@link resolveAuth} with a "must have a managing tenant" gate.
 * Tenant-scoped pages (Aircraft, Locations, Persons, Flights, future
 * Reservations / Members) render empty under {@code @TenantId} filtering
 * when the principal has no {@code clubId}.
 *
 * <p>A tenant-bearing session passes through. A SYSTEM_ADMINISTRATOR has no
 * {@code clubId} claim but legitimately operates club-less, so it lands on
 * {@code /start}. Any other tenant-less principal is an onboarding pilot with
 * no {@code t_user} yet: route it into the self-serve join flow —
 * {@code /join/pending} when a live (non-final) {@code JoinRequest} is waiting,
 * else {@code /join} to enter a code. The live-request probe is a single GET
 * the pilot is authorized for (they own the request); this backs up the
 * server-side JIT 403 (S-179).
 *
 * <p>The {@code currentClubId()} read is deferred behind {@link resolveAuth}
 * so that, during the transient session-loading window, it is evaluated only
 * AFTER the session settles (when {@code loadMe()} has populated the club id)
 * — never against the transiently-null loading state, which used to bounce a
 * mid-renew navigation.
 */
export const tenantRequiredGuard: CanActivateFn = (route, state) => {
  const session = inject(SessionStore);
  const router = inject(Router);
  const joinRequests = inject(JoinRequestsService);

  const tenantGate = (): boolean | Observable<boolean | UrlTree> => {
    if (session.currentClubId() !== null) {
      return true;
    }
    if (session.isSystemAdmin()) {
      return of(router.parseUrl('/start'));
    }
    // A tenant-less non-admin is mid-onboarding: probe the live request to
    // choose the pending page vs the code entry. A 204/failure resolves to
    // `null` (no live request) → `/join`.
    return joinRequests
      .myJoinRequest<JoinRequestResponse | null>()
      .pipe(map((request) => router.parseUrl(onboardingRedirect(request ?? null))));
  };

  return composeAfterAuth(resolveAuth(route, state), tenantGate);
};
