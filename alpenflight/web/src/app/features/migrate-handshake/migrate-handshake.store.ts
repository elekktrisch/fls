import { HttpErrorResponse } from '@angular/common/http';
import { computed, inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import {
  patchState,
  signalStore,
  withComputed,
  withMethods,
  withState,
} from '@ngrx/signals';
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

/**
 * Drives the {@code /migrate/start} page. Two transitions matter:
 *
 * <ul>
 *   <li>{@link MigrateHandshakeStore#restoreOrIssue}: page mount —
 *       try {@code GET .../current}; on 404 fall through to
 *       {@code POST .../handshake}. Restoring the existing row protects
 *       against accidental supersession on a refresh / browser-back.</li>
 *   <li>{@link MigrateHandshakeStore#regenerate}: user clicked the
 *       Regenerate button + confirmed the modal — {@code POST .../handshake}
 *       silently supersedes the prior row.</li>
 * </ul>
 */
export const MigrateHandshakeStore = signalStore(
  { providedIn: 'root' },
  withState<MigrateHandshakeState>(initial),
  withComputed(({ upload, isLoading, hasError }) => ({
    hasUpload: computed(() => upload() !== null),
    showLoading: computed(() => isLoading() && upload() === null),
    showError: computed(() => hasError() && upload() === null),
  })),
  withMethods((store, api = inject(MigrateHandshakeService)) => {
    const setLoading = (): void =>
      patchState(store, { isLoading: true, hasError: false });
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
                  // No in-flight row — fire a fresh POST. The supersede
                  // modal is suppressed because there is no prior key to
                  // invalidate from the user's perspective.
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
