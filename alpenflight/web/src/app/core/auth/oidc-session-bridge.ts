import { DestroyRef, Injectable, effect, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { EventTypes, OidcSecurityService, PublicEventsService } from 'angular-auth-oidc-client';
import { filter } from 'rxjs';

import { SessionStore, type User } from '../session/session.store';

import { mapClaimsToUser } from './oidc-claims';
import { DEFAULT_POST_LOGIN_ROUTE, consumePostLoginRedirect } from './post-login-redirect';

export interface SessionPort {
  login(user: User, clubId: string | null): void;
  logout(): void;
  markUnauthenticated(): void;
  isAuthenticated(): boolean;
  isLoadingSession(): boolean;
  bootstrapPrefetch(): void;
  // S-165: after the JWT-derived User is in place, /me upgrades
  // `personId` + linked-Person firstName/lastName so the home
  // dashboard can filter the flights list by the caller's Person.
  loadMe(): void;
}

/**
 * Pure handler for OIDC `userData()` signal emissions. Extracted from the
 * service so unit tests can exercise the branching without bootstrapping
 * the OIDC library.
 *
 * Three transitions:
 *   - claims present     → login (sessionStatus = 'authenticated')
 *   - claims absent + was authenticated → logout (fires bus event)
 *   - claims absent + still idle/loading → markUnauthenticated (settles
 *     status without firing the bus; cold-start path so no stores to clear)
 */
export function applyClaimsToSession(claims: unknown, session: SessionPort): void {
  const user = mapClaimsToUser(claims);
  if (user) {
    session.login(user, user.clubId);
    session.bootstrapPrefetch();
    session.loadMe();
    return;
  }
  if (session.isAuthenticated()) {
    session.logout();
    return;
  }
  if (session.isLoadingSession()) {
    session.markUnauthenticated();
  }
}

/**
 * Pure handler for `EventTypes.SilentRenewFailed`. Order matters: the
 * session is cleared FIRST so a concurrent route activation does not see
 * a stale authenticated user before the redirect lands.
 */
export function handleSilentRenewFailed(session: SessionPort, reauthorize: () => void): void {
  session.logout();
  reauthorize();
}

/**
 * Wires the angular-auth-oidc-client signals + events into SessionStore.
 * No other code should inject {@link OidcSecurityService}: SessionStore is
 * the single read seam for application code.
 */
@Injectable({ providedIn: 'root' })
export class OidcSessionBridge {
  private readonly oidc = inject(OidcSecurityService);
  private readonly events = inject(PublicEventsService);
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  constructor() {
    effect(() => {
      const userDataResult = this.oidc.userData();
      applyClaimsToSession(userDataResult?.userData ?? null, this.session);
    });

    // After Keycloak callback the lib processes ?code= and emits
    // NewAuthenticationResult before userData() settles. The remembered
    // URL is consumed once per redirect; fallback to the post-auth
    // default landing route. `triggerAuthorizationResultEvent: true`
    // suppresses the lib's own navigateByUrl(postLoginRoute).
    this.events
      .registerForEvents()
      .pipe(
        filter((e) => e.type === EventTypes.NewAuthenticationResult),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        const target = consumePostLoginRedirect() ?? DEFAULT_POST_LOGIN_ROUTE;
        this.router.navigateByUrl(target);
      });

    this.events
      .registerForEvents()
      .pipe(
        filter((e) => e.type === EventTypes.SilentRenewFailed),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        handleSilentRenewFailed(this.session, () => this.oidc.authorize());
      });
  }
}
