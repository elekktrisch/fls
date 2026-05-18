import { computed, inject } from '@angular/core';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';

import { MUTATION_BUS } from '../mutation-bus/mutation-bus';

// Realm roles from `next/auth/realm-export.json`. Mirrored from the
// `realm_access.roles[]` claim Keycloak stamps onto access + id tokens.
// The mapper at `core/auth/oidc-claims.ts` filters unknown realm roles
// (e.g. Keycloak built-ins `uma_authorization`, `offline_access`) so the
// store's role list is always the AlpenFlight catalog.
export type AppRole =
  | 'SYSTEM_ADMINISTRATOR'
  | 'CLUB_ADMINISTRATOR'
  | 'FLIGHT_OPERATOR'
  | 'PILOT'
  | 'OFFICE_USER'
  | 'GUEST';

export interface User {
  id: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  // Nullable: federated / not-yet-imported users carry no `clubId` claim.
  // S-022's `ClubTenantIdentifierResolver` falls back to a DB lookup by
  // `keycloak_sub` / email; the SPA shows "no club selected" while that
  // resolves.
  clubId: string | null;
  roles: readonly AppRole[];
}

// SECURITY: state declares ONLY claims-derived data. NEVER add access_token,
// refresh_token, or id_token here — those live in the OIDC library's storage
// layer (S-021 selects iframe vs cookie). Signals are trivially readable from
// dev tools.
type SessionStatus = 'idle' | 'loading' | 'authenticated' | 'unauthenticated';

interface SessionState {
  authenticatedUser: User | null;
  currentClubId: string | null;
  sessionStatus: SessionStatus;
  bootstrapStartedAt: number | null;
}

const initial: SessionState = {
  authenticatedUser: null,
  currentClubId: null,
  sessionStatus: 'idle',
  bootstrapStartedAt: null,
};

export const SessionStore = signalStore(
  { providedIn: 'root' },
  withState(initial),
  withComputed(({ authenticatedUser, sessionStatus }) => ({
    isAuthenticated: computed(
      () => sessionStatus() === 'authenticated' && authenticatedUser() !== null,
    ),
    isLoadingSession: computed(() => sessionStatus() === 'idle' || sessionStatus() === 'loading'),
    isClubAdmin: computed(() => authenticatedUser()?.roles.includes('CLUB_ADMINISTRATOR') ?? false),
    isSystemAdmin: computed(
      () => authenticatedUser()?.roles.includes('SYSTEM_ADMINISTRATOR') ?? false,
    ),
  })),
  withMethods((store, bus = inject(MUTATION_BUS)) => ({
    login(user: User, clubId: string | null): void {
      patchState(store, {
        authenticatedUser: user,
        currentClubId: clubId,
        sessionStatus: 'authenticated',
      });
    },
    logout(): void {
      patchState(store, { ...initial, sessionStatus: 'unauthenticated' });
      bus.next({ kind: 'session.logout' });
    },
    // Boot-finished-with-no-user transition. Distinct from logout(): no
    // MUTATION_BUS event is fired since no domain store was ever loaded.
    // Required so authGuard exits its loading-defer branch and triggers
    // oidcSecurity.authorize() — without this the cold-start path stalls
    // on sessionStatus = 'idle' indefinitely.
    markUnauthenticated(): void {
      patchState(store, { ...initial, sessionStatus: 'unauthenticated' });
    },
    /**
     * AC-DIR-1 aggressive-prefetch seam. Called by S-021's OIDC success
     * handler after `login()` and by the tenant-switch UI after the active
     * `currentClubId` changes. Per-domain prefetch wiring lands at S-047+;
     * today this method only stamps the bootstrap marker so future
     * implementers can see the seam.
     */
    bootstrapPrefetch(): void {
      if (sessionStatusIsLoading(store.sessionStatus()) || !store.isAuthenticated()) {
        return;
      }
      patchState(store, { bootstrapStartedAt: Date.now() });
      // TODO(S-047+): forkJoin([aircraftsStore.loadAll(), personsStore.loadAll(), ...])
      // with per-stream catchError(() => of(null)) so one slow endpoint
      // doesn't stall the whole bootstrap. Cancellation rides session.logout
      // through MUTATION_BUS.
    },
  })),
);

function sessionStatusIsLoading(status: SessionStatus): boolean {
  return status === 'idle' || status === 'loading';
}
