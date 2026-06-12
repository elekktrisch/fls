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

// Realm roles from `alpenflight/auth/realm-export.json`. Mirrored from the
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
  // Nullable: sysadmins + federated users without a linked Person carry
  // none. Populated by `loadMe()` after auth from the `/api/v1/me`
  // projection (S-165). Drives the home-dashboard "Your last flight"
  // card and any future per-person filter (admin views pilot X's
  // flights, view-as, per-person stats).
  personId: string | null;
  // Nullable: the caller's club homebase Location id (`loc-<uuid>`),
  // populated by `loadMe()` from the `/api/v1/me` projection (J-7 T-09b).
  // Drives LOCATION canned reports — the reporting page passes it as the
  // report filter's `locationId`. Null when the club has no homebase set.
  homebaseLocationId: string | null;
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
                // Cold-start locale precedence (J-4 T-20): the app-initializer
                // already ran `LocaleService.set(?lang= → navigator → de)`
                // synchronously at bootstrap. Now that `/me` resolved, an
                // authenticated user's PERSISTED `languageCode` takes
                // precedence over the navigator/default fallback — so a saved
                // language survives reload/next login. An explicit `?lang=`
                // override always wins (operator-pinned), so it is NOT
                // overridden here; an unmappable code (e.g. `rm`) leaves the
                // cold-start locale in place.
                const urlSearch = typeof window !== 'undefined' ? window.location.search : null;
                if (!hasExplicitLangOverride(urlSearch)) {
                  const persisted = localeForLanguageCode(response.languageCode);
                  if (persisted !== null && persisted !== locale.current()) {
                    locale.set(persisted);
                  }
                }
                // /me is the source of truth for {personId, clubId,
                // firstName, lastName, email, username, id} post-auth. JWT
                // claims seeded the initial values pre-/me-resolution; this
                // patch upgrades them to the server's authoritative view
                // (linked Person row > JWT claim). Roles stay JWT-sourced —
                // /me echoes the same realm list and the JWT-derived
                // GrantedAuthority is what hasRole() reads.
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
                // Tolerant — a 401 / 5xx leaves the JWT-seeded fields in
                // place. The home dashboard renders its empty state when
                // personId is null; no toast, no log noise per the AC.
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
         * `currentClubId` changes. Each store owns its own catchError-per-stream
         * forkJoin internally; this method just fans out the `loadAll()` calls.
         * Cancellation rides session.logout through MUTATION_BUS — every store
         * subscribes and self-clears.
         */
        bootstrapPrefetch(): void {
          if (sessionStatusIsLoading(store.sessionStatus()) || !store.isAuthenticated()) {
            return;
          }
          patchState(store, { bootstrapStartedAt: Date.now() });
          refData.loadAll();
          // Future masterdata stores (aircraft, persons, locations, flight-types,
          // routes) plug in here as they land.
        },
        loadMe,
      };
    },
  ),
  withHooks({
    onInit(store) {
      const bus = inject(MUTATION_BUS);
      const destroyRef = inject(DestroyRef);
      // The /profile Account self-edit (J-4) emits `profile.updated` after a
      // successful PATCH /api/v1/me/profile. Re-read /me so the nav avatar +
      // session-backed consumers reflect the new friendlyName / email /
      // language without a page reload. Cross-store coordination via the bus
      // (no direct AccountStore→SessionStore injection).
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
