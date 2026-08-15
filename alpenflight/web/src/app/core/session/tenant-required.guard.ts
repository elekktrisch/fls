import { inject } from '@angular/core';
import { Router, type CanActivateFn, type UrlTree } from '@angular/router';
import { type Observable, map } from 'rxjs';

import { JoinRequestsService } from '@api/generated/join-requests/join-requests.service';
import type { JoinRequestResponse } from '@api/generated/model';

import { composeAfterAuth, resolveAuth } from './session.guard';
import { SessionStore } from './session.store';

export function onboardingRedirect(request: JoinRequestResponse | null): '/join/pending' | '/join' {
  return request?.status === 'PENDING' ? '/join/pending' : '/join';
}

export const tenantRequiredGuard: CanActivateFn = (route, state) => {
  const session = inject(SessionStore);
  const router = inject(Router);
  const joinRequests = inject(JoinRequestsService);

  const targetIsStart = (): boolean => {
    return router.parseUrl(state.url).root.children['primary']?.segments[0]?.path === 'start';
  };

  const tenantGate = (): boolean | UrlTree | Observable<boolean | UrlTree> => {
    if (session.currentClubId() !== null) {
      return true;
    }
    if (session.isSystemAdmin()) {
      return targetIsStart() ? true : router.parseUrl('/start');
    }
    return joinRequests
      .myJoinRequest<JoinRequestResponse | null>()
      .pipe(map((request) => router.parseUrl(onboardingRedirect(request ?? null))));
  };

  return composeAfterAuth(resolveAuth(route, state), tenantGate);
};
