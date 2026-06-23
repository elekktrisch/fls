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

/**
 * Resolves authentication for a private route to `true` (pass) or `false`
 * (unauthenticated → hard-401 redirect to Keycloak per [ADR 0007]).
 *
 * Public routes (`data.publicAccess === true`) pass without consulting the
 * session.
 *
 * **Wait-for-settle, not cancel.** While the session is still resolving
 * (`isLoadingSession()` — the transient idle/loading window during cold
 * boot or an OIDC silent token renew) the guard WAITS for the status to
 * settle before evaluating, returning an `Observable<boolean>` that emits
 * once `isLoadingSession()` flips to `false`. A `CanActivateFn` that
 * returns `false` CANCELS the navigation — so returning `false` during the
 * loading window silently killed any navigation that raced a renew (a
 * navigation-during-renew bug surfaced in J-2). Waiting lets the real user's click /
 * deep-link refresh during a renew complete once auth settles instead of
 * dying. Under the normal boot path `withAppInitializerAuthCheck()` blocks
 * bootstrap until the status settles, so the wait branch is usually a
 * no-op; mock-auth's session is synchronously settled, so it never fires.
 *
 * Returns a synchronous value when the session is already settled (keeps
 * the prior behavior exactly), and an `Observable` only while loading.
 */
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
    // Bridge the loading signal to an observable, wait for it to settle to
    // `false`, then evaluate once. Signal→observable is zoneless-safe (no
    // zone-dependent timers); `take(1)` completes the stream so the guard
    // resolves and never leaks a subscription.
    return toObservable(session.isLoadingSession).pipe(
      filter((loading) => !loading),
      take(1),
      map(() => evaluate()),
    );
  }

  return evaluate();
}

/**
 * Default-deny route guard. Public routes opt-in via `data.publicAccess === true`.
 *
 * Reads SessionStore (the only seam app code touches) and falls back to the
 * OIDC library when an unauthenticated principal hits a private route — a
 * hard redirect to Keycloak per [ADR 0007] §"Hard-401 redirect." Waits for
 * a still-resolving session to settle rather than cancelling the navigation
 * (see {@link resolveAuth}).
 */
export const authGuard: CanActivateFn = (route, state) => resolveAuth(route, state);

/**
 * Composes {@link resolveAuth} with an additional gate that only runs once
 * auth has resolved `true`. Threads both the synchronous-settled and the
 * waiting-on-loading paths: a settled session evaluates the extra check
 * immediately; a loading session waits for settle (via the auth observable)
 * before the extra check sees a populated SessionStore. When auth resolves
 * to anything other than `true` (redirect/cancel), that result passes
 * through untouched — so the ADR 0007 hard-401 path is preserved verbatim.
 *
 * The extra check itself may resolve asynchronously (e.g. a tenant-less
 * onboarding probe that GETs the live join request): an `Observable` it
 * returns is flattened so the guard yields a single boolean/UrlTree.
 */
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
