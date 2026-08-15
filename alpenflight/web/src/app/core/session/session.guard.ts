import { inject } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import {
  type ActivatedRouteSnapshot,
  type CanActivateFn,
  type RouterStateSnapshot,
  type UrlTree,
} from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { type Observable, filter, isObservable, map, of, switchMap, take } from 'rxjs';

import { rememberPostLoginRedirect } from '../auth/post-login-redirect';
import { SessionStore } from './session.store';

export function resolveAuth(
  route: ActivatedRouteSnapshot,
  state: RouterStateSnapshot,
): boolean | Observable<boolean> {
  const session = inject(SessionStore);
  const oidc = inject(OidcSecurityService);

  if (route.data['publicAccess'] === true) {
    return true;
  }

  const evaluate = (): boolean => {
    if (session.isAuthenticated()) {
      return true;
    }
    rememberPostLoginRedirect(state.url);
    oidc.authorize();
    return false;
  };

  if (session.isLoadingSession()) {
    return toObservable(session.isLoadingSession).pipe(
      filter((loading) => !loading),
      take(1),
      map(() => evaluate()),
    );
  }

  return evaluate();
}

export const authGuard: CanActivateFn = (route, state) => resolveAuth(route, state);

export function composeAfterAuth(
  authResult: boolean | Observable<boolean>,
  extraCheck: () => boolean | UrlTree | Observable<boolean | UrlTree>,
): boolean | UrlTree | Observable<boolean | UrlTree> {
  if (typeof authResult === 'boolean') {
    return authResult ? extraCheck() : authResult;
  }
  return authResult.pipe(
    switchMap((authed) => {
      if (!authed) {
        return of(authed);
      }
      const checked = extraCheck();
      return isObservable(checked) ? checked : of(checked);
    }),
  );
}
