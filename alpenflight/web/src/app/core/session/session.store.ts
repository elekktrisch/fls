import { DestroyRef, computed, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  patchState,
  signalStore,
  withComputed,
  withHooks,
  withMethods,
  withState,
} from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { EMPTY, catchError, pipe, switchMap, tap } from 'rxjs';

import { LocaleService } from '@shared/ui/locale';

import { hasExplicitLangOverride, localeForLanguageCode } from '../i18n';
import { MUTATION_BUS } from '../mutation-bus/mutation-bus';
import { ReferenceDataStore } from '../reference-data/reference-data.store';

import { MeService } from './me.service';

// ext: Keycloak realm_access.roles values
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
  clubId: string | null;
  personId: string | null;
  homebaseLocationId: string | null;
  roles: readonly AppRole[];
}

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
    isFlightOperator: computed(
      () => authenticatedUser()?.roles.includes('FLIGHT_OPERATOR') ?? false,
    ),
  })),
  withMethods(
    (
      store,
      bus = inject(MUTATION_BUS),
      refData = inject(ReferenceDataStore),
      me = inject(MeService),
      locale = inject(LocaleService),
    ) => {
      const loadMe = rxMethod<void>(
        pipe(
          switchMap(() =>
            me.getMe().pipe(
              tap((response) => {
                const current = store.authenticatedUser();
                if (!current) {
                  return;
                }
                const urlSearch = typeof window !== 'undefined' ? window.location.search : null;
                if (!hasExplicitLangOverride(urlSearch)) {
                  const persisted = localeForLanguageCode(response.languageCode);
                  if (persisted !== null && persisted !== locale.current()) {
                    locale.set(persisted);
                  }
                }
                patchState(store, {
                  authenticatedUser: {
                    ...current,
                    id: response.id ?? current.id,
                    personId: response.personId,
                    clubId: response.clubId ?? current.clubId,
                    firstName: response.firstName ?? current.firstName,
                    lastName: response.lastName ?? current.lastName,
                    email: response.email ?? current.email,
                    username: response.username ?? current.username,
                    homebaseLocationId: response.homebaseLocationId,
                  },
                  currentClubId: response.clubId ?? store.currentClubId(),
                });
              }),
              catchError(() => {
                return EMPTY;
              }),
            ),
          ),
        ),
      );
      return {
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
        markUnauthenticated(): void {
          patchState(store, { ...initial, sessionStatus: 'unauthenticated' });
        },
        bootstrapPrefetch(): void {
          if (sessionStatusIsLoading(store.sessionStatus()) || !store.isAuthenticated()) {
            return;
          }
          patchState(store, { bootstrapStartedAt: Date.now() });
          refData.loadAll();
        },
        loadMe,
      };
    },
  ),
  withHooks({
    onInit(store) {
      const bus = inject(MUTATION_BUS);
      const destroyRef = inject(DestroyRef);
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        if (evt.kind === 'profile.updated') {
          store.loadMe();
        }
      });
    },
  }),
);

function sessionStatusIsLoading(status: SessionStatus): boolean {
  return status === 'idle' || status === 'loading';
}
