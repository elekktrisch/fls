import { DestroyRef, Injectable, effect, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { EventTypes, OidcSecurityService, PublicEventsService } from 'angular-auth-oidc-client';
import { filter } from 'rxjs';

import { DemoSeatSession, claimsOfAccessToken } from '../session/demo-seat.session';
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
  loadMe(): void;
}

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

export function applyClaimsUnlessADemoSeatOwnsTheSession(
  claims: unknown,
  session: SessionPort,
  aDemoSeatOwnsTheSession: boolean,
): void {
  if (aDemoSeatOwnsTheSession) {
    return;
  }
  applyClaimsToSession(claims, session);
}

export function restoreTheDemoSeatSessionThatSurvivedAPageReload(): void {
  const heldSeatToken = inject(DemoSeatSession).accessToken();
  if (heldSeatToken === null) {
    return;
  }
  applyClaimsToSession(claimsOfAccessToken(heldSeatToken), inject(SessionStore));
}

export function handleSilentRenewFailed(session: SessionPort, reauthorize: () => void): void {
  session.logout();
  reauthorize();
}

function isSilentRenewResult(authenticationResult: unknown): boolean {
  return (
    typeof authenticationResult === 'object' &&
    authenticationResult !== null &&
    (authenticationResult as { isRenewProcess?: unknown }).isRenewProcess === true
  );
}

export function postLoginTargetUnlessASilentRenewMustLeaveTheUserOnTheCurrentPage(
  authenticationResult: unknown,
): string | null {
  if (isSilentRenewResult(authenticationResult)) {
    return null;
  }
  return consumePostLoginRedirect() ?? DEFAULT_POST_LOGIN_ROUTE;
}

@Injectable({ providedIn: 'root' })
export class OidcSessionBridge {
  private readonly oidc = inject(OidcSecurityService);
  private readonly events = inject(PublicEventsService);
  private readonly session = inject(SessionStore);
  private readonly demoSeat = inject(DemoSeatSession);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  constructor() {
    effect(() => {
      const userDataResult = this.oidc.userData();
      applyClaimsUnlessADemoSeatOwnsTheSession(
        userDataResult?.userData ?? null,
        this.session,
        this.demoSeat.isLive(),
      );
    });

    this.events
      .registerForEvents()
      .pipe(
        filter((e) => e.type === EventTypes.NewAuthenticationResult),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((event) => {
        const target = postLoginTargetUnlessASilentRenewMustLeaveTheUserOnTheCurrentPage(
          event.value,
        );
        if (target !== null) {
          void this.router.navigateByUrl(target);
        }
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
