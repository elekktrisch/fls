import { computed, inject } from '@angular/core';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';

import { MUTATION_BUS } from '../mutation-bus/mutation-bus';

export interface User {
  id: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  clubId: string;
  roles: readonly ('CLUB_ADMIN' | 'SYSTEM_ADMIN' | 'MEMBER')[];
}

// SECURITY: state declares ONLY claims-derived data. NEVER add access_token,
// refresh_token, or id_token here — those live in the OIDC library's storage
// layer (S-021 selects iframe vs cookie). Signals are trivially readable from
// dev tools.
interface SessionState {
  authenticatedUser: User | null;
  currentClubId: string | null;
  sessionStatus: 'idle' | 'loading' | 'authenticated' | 'unauthenticated';
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
    isClubAdmin: computed(() => authenticatedUser()?.roles.includes('CLUB_ADMIN') ?? false),
    isSystemAdmin: computed(() => authenticatedUser()?.roles.includes('SYSTEM_ADMIN') ?? false),
  })),
  withMethods((store, bus = inject(MUTATION_BUS)) => ({
    // TODO(S-021): replace placeholder body with OIDC callback handler.
    login(user: User, clubId: string): void {
      patchState(store, {
        authenticatedUser: user,
        currentClubId: clubId,
        sessionStatus: 'authenticated',
      });
    },
    // TODO(S-021): wire to OIDC logout endpoint + token revocation.
    logout(): void {
      patchState(store, { ...initial, sessionStatus: 'unauthenticated' });
      bus.next({ kind: 'session.logout' });
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

function sessionStatusIsLoading(status: SessionState['sessionStatus']): boolean {
  return status === 'idle' || status === 'loading';
}
