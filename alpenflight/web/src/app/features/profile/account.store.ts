import { computed, inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import { MeService } from '@api/generated/me/me.service';
import type { MeProfileUpdateRequest, MeResponse } from '@api/generated/model';
import { LocaleService, localeForLanguageId } from '@shared/ui/locale';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

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
    notificationEmail: res.email ?? '',
    phoneNumber: res.phoneNumber ?? '',
    languageId: res.languageId ?? '',
    username: res.username ?? '',
    clubId: res.clubId ?? '',
  };
}

export const AccountStore = signalStore(
  withState<AccountState>(initial),
  withComputed(({ view, isSaving }) => ({
    canSave: computed(() => view() !== null && !isSaving()),
  })),
  withMethods(
    (store, me = inject(MeService), locale = inject(LocaleService), bus = inject(MUTATION_BUS)) => {
      const applyLocale = (languageId: string): void => {
        const next = localeForLanguageId(languageId);
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
                  applyLocale(res.languageId ?? req.languageId);
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
