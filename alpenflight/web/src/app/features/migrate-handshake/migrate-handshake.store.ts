import { HttpErrorResponse } from '@angular/common/http';
import { computed, inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import type { HandshakeResponse } from './migrate-handshake.service';
import { MigrateHandshakeService } from './migrate-handshake.service';

interface MigrateHandshakeState {
  upload: HandshakeResponse | null;
  isLoading: boolean;
  hasError: boolean;
}

const initial: MigrateHandshakeState = {
  upload: null,
  isLoading: false,
  hasError: false,
};

export const MigrateHandshakeStore = signalStore(
  { providedIn: 'root' },
  withState<MigrateHandshakeState>(initial),
  withComputed(({ upload, isLoading, hasError }) => ({
    hasUpload: computed(() => upload() !== null),
    showLoading: computed(() => isLoading() && upload() === null),
    showError: computed(() => hasError() && upload() === null),
  })),
  withMethods((store, api = inject(MigrateHandshakeService)) => {
    const setLoading = (): void => patchState(store, { isLoading: true, hasError: false });
    const setSuccess = (response: HandshakeResponse): void =>
      patchState(store, { upload: response, isLoading: false, hasError: false });
    const setError = (): void => patchState(store, { isLoading: false, hasError: true });

    const issueFresh = rxMethod<void>(
      pipe(
        tap(setLoading),
        switchMap(() =>
          api.issue().pipe(
            tapResponse({
              next: setSuccess,
              error: setError,
            }),
          ),
        ),
      ),
    );

    const restoreOrIssue = rxMethod<void>(
      pipe(
        tap(setLoading),
        switchMap(() =>
          api.current().pipe(
            tapResponse({
              next: setSuccess,
              error: (error: HttpErrorResponse) => {
                if (error.status === 404) {
                  issueFresh();
                } else {
                  setError();
                }
              },
            }),
          ),
        ),
      ),
    );

    return {
      restoreOrIssue: () => restoreOrIssue(),
      regenerate: () => issueFresh(),
      reset: (): void => patchState(store, initial),
    };
  }),
);
