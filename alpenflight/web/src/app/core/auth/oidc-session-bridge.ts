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

export function handleSilentRenewFailed(session: SessionPort, reauthorize: () => void): void {
  session.logout();
  reauthorize();
}

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
