import { computed, inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import { MeService } from '@api/generated/me/me.service';
import type { MeProfileUpdateRequest, MeResponse } from '@api/generated/model';
import { LocaleService, localeForLanguageId } from '@shared/ui/locale';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

/**
 * The Account self-edit values the form binds to. Sourced from the `/api/v1/me`
 * projection (J-4 extended it with `friendlyName` / `phoneNumber` /
 * `languageId` / `languageCode`). `username` + `clubId` are read-only display
 * fields; the rest are editable and round-trip through
 * {@code PATCH /api/v1/me/profile} (`updateMyProfile`).
 */
export interface AccountView {
  friendlyName: string;
  notificationEmail: string;
  phoneNumber: string;
  languageId: string;
  username: string;
  clubId: string;
}

interface AccountState {
  view: AccountView | null;
  isLoading: boolean;
  isSaving: boolean;
  hasError: boolean;
  // True once a save has persisted — drives the inline "saved" confirmation.
  savedOnce: boolean;
}

const initial: AccountState = {
  view: null,
  isLoading: false,
  isSaving: false,
  hasError: false,
  savedOnce: false,
};

function toView(res: MeResponse): AccountView {
  return {
    friendlyName: res.friendlyName ?? '',
    // `email` IS the notification email in the /me projection (distinct from
    // the Keycloak login email).
    notificationEmail: res.email ?? '',
    phoneNumber: res.phoneNumber ?? '',
    languageId: res.languageId ?? '',
    username: res.username ?? '',
    clubId: res.clubId ?? '',
  };
}

/**
 * Account-tab store (J-4 T-05). Loads the caller's own Account fields from
 * `/api/v1/me` and persists edits through `PATCH /api/v1/me/profile`
 * (orval `updateMyProfile`). On a saved language change it flips the SPA's
 * active locale via {@link LocaleService} and emits a `profile.updated`
 * MUTATION_BUS event so the session re-reads `/me` (the nav avatar + any other
 * `/me` consumer reflect the new values) — coordinated via the bus rather than
 * a direct SessionStore injection (no-sibling-store rule, CLAUDE.md §10).
 *
 * Feature-scoped (not `providedIn: 'root'`): the store's lifetime is the
 * `/profile` route, and a fresh load on every visit is the desired behavior.
 */
export const AccountStore = signalStore(
  withState<AccountState>(initial),
  withComputed(({ view, isSaving }) => ({
    canSave: computed(() => view() !== null && !isSaving()),
  })),
  withMethods(
    (store, me = inject(MeService), locale = inject(LocaleService), bus = inject(MUTATION_BUS)) => {
      const applyLocale = (languageId: string): void => {
        const next = localeForLanguageId(languageId);
        // Only flip when the chosen language maps to an SPA-supported locale
        // (de/fr/it/en). A migrated user on de-CH / rm keeps the active locale.
        if (next !== null && next !== locale.current()) {
          locale.set(next);
        }
      };

      const load = rxMethod<void>(
        pipe(
          tap(() => patchState(store, { isLoading: true, hasError: false })),
          switchMap(() =>
            me.get1().pipe(
              tapResponse({
                next: (res: MeResponse) =>
                  patchState(store, { view: toView(res), isLoading: false }),
                error: () => patchState(store, { isLoading: false, hasError: true }),
              }),
            ),
          ),
        ),
      );

      const save = rxMethod<MeProfileUpdateRequest>(
        pipe(
          tap(() => patchState(store, { isSaving: true, hasError: false })),
          switchMap((req) =>
            me.updateMyProfile(req).pipe(
              tapResponse({
                next: (res: MeResponse) => {
                  patchState(store, {
                    view: toView(res),
                    isSaving: false,
                    savedOnce: true,
                  });
                  // Persisted languageId → flip the SPA locale.
                  applyLocale(res.languageId ?? req.languageId);
                  // Nudge the session to re-read /me so the nav avatar +
                  // session-backed consumers reflect the new values.
                  bus.next({ kind: 'profile.updated' });
                },
                error: () => patchState(store, { isSaving: false, hasError: true }),
              }),
            ),
          ),
        ),
      );

      return {
        load,
        save,
        clearError(): void {
          patchState(store, { hasError: false });
        },
      };
    },
  ),
);
