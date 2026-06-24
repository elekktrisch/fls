import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import { JoinRequestsService } from '@api/generated/join-requests/join-requests.service';
import type { JoinRequestResponse } from '@api/generated/model';
import { classifyApiError, type SaveErrorRule } from '@shared/util/form';

/**
 * Why each submit failure carries its own kind: the `/join` form renders them
 * differently — `unknown-code` inline under the field, `already-member` as a
 * sign-out prompt, `rate-limited` as a `Retry-After` countdown. Keeping the
 * discriminant structured here lets T-10/T-11 branch without re-parsing status.
 */
export type SubmitErrorKind = 'unknown-code' | 'already-member' | 'rate-limited' | 'other';

export interface SubmitError {
  readonly kind: SubmitErrorKind;
  readonly message: string;
  /** Seconds the pilot must wait before retrying — present only for `rate-limited`. */
  readonly retryAfterSeconds?: number;
}

interface JoinState {
  request: JoinRequestResponse | null;
  isSubmitting: boolean;
  submitError: SubmitError | null;
}

const initial: JoinState = {
  request: null,
  isSubmitting: false,
  submitError: null,
};

interface SubmitArgs {
  joinCode: string;
  note?: string;
}

/**
 * Pilot-side join-request store. Feature-scoped (not `providedIn: 'root'`): its
 * lifetime is the `/join` + `/join/pending` flow, and a fresh `loadMine()` on
 * entry is the desired behavior. Owns the three pilot calls — submit, withdraw,
 * and `me/join-request` — over the generated client.
 */
export const JoinStore = signalStore(
  withState<JoinState>(initial),
  withMethods((store, api = inject(JoinRequestsService)) => {
    const loadMine = rxMethod<void>(
      pipe(
        switchMap(() =>
          api.myJoinRequest().pipe(
            tapResponse({
              // A 204 (no live request) resolves to a null body — back to /join.
              next: (request: JoinRequestResponse | null) =>
                patchState(store, { request: request ?? null }),
              error: () => patchState(store, { request: null }),
            }),
          ),
        ),
      ),
    );

    const submit = rxMethod<SubmitArgs>(
      pipe(
        tap(() => patchState(store, { isSubmitting: true, submitError: null })),
        switchMap(({ joinCode, note }) =>
          api.submit({ joinCode, ...(note ? { note } : {}) }).pipe(
            tapResponse({
              next: (request: JoinRequestResponse) =>
                patchState(store, { request, isSubmitting: false }),
              error: (e: HttpErrorResponse) =>
                patchState(store, { isSubmitting: false, submitError: toSubmitError(e) }),
            }),
          ),
        ),
      ),
    );

    const withdraw = rxMethod<string>(
      pipe(
        // Optimistically drop the held request up-front: the pending page
        // navigates to `/join` the moment the pilot clicks Withdraw, and the
        // `/join` screen bounces a STILL-PENDING held request straight back to
        // `/join/pending` — racing the eager nav into an `AbortError`. Clearing
        // before the HTTP round-trip means the `/join` screen sees no live
        // request and stays put.
        tap(() => patchState(store, { request: null })),
        switchMap((id) =>
          api.withdraw(id).pipe(
            // A withdraw returns the pilot to /join with no live request and no
            // cooldown. The optimistic clear above already emptied the held
            // request; re-assert null on settle so an error path converges too.
            tapResponse({
              next: () => patchState(store, { request: null }),
              error: () => patchState(store, { request: null }),
            }),
          ),
        ),
      ),
    );

    return {
      clearSubmitError(): void {
        patchState(store, { submitError: null });
      },
      loadMine,
      submit(joinCode: string, note?: string): void {
        submit({ joinCode, ...(note !== undefined ? { note } : {}) });
      },
      withdraw,
    };
  }),
);

const submitErrorRules: readonly SaveErrorRule<SubmitErrorKind>[] = [
  {
    status: 404,
    outcome: (b) => ({
      saveError: b.detail ?? 'Check the code with your club admin.',
      saveErrorKind: 'unknown-code',
    }),
  },
  {
    status: 409,
    outcome: (b) => ({
      saveError: b.detail ?? "You're already in a club. Sign out to switch clubs.",
      saveErrorKind: 'already-member',
    }),
  },
  {
    status: 429,
    outcome: (b) => ({
      saveError: b.detail ?? 'Too many attempts — try again later.',
      saveErrorKind: 'rate-limited',
    }),
  },
];

function toSubmitError(e: HttpErrorResponse): SubmitError {
  const { saveError, saveErrorKind } = classifyApiError(e, submitErrorRules, (body) => ({
    saveError: body?.detail ?? body?.message ?? e.message,
    saveErrorKind: 'other',
  }));
  const retryAfterSeconds = saveErrorKind === 'rate-limited' ? retryAfterFrom(e) : undefined;
  return {
    kind: saveErrorKind,
    message: saveError,
    ...(retryAfterSeconds !== undefined ? { retryAfterSeconds } : {}),
  };
}

function retryAfterFrom(e: HttpErrorResponse): number | undefined {
  const header = e.headers.get('Retry-After');
  if (header === null) return undefined;
  const seconds = Number.parseInt(header, 10);
  return Number.isNaN(seconds) ? undefined : seconds;
}
