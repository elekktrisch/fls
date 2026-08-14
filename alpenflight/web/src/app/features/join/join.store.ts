import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import { JoinRequestsService } from '@api/generated/join-requests/join-requests.service';
import type { JoinRequestResponse } from '@api/generated/model';
import { classifyApiError, type SaveErrorRule } from '@shared/util/form';

export type SubmitErrorKind = 'unknown-code' | 'already-member' | 'rate-limited' | 'other';

export interface SubmitError {
  readonly kind: SubmitErrorKind;
  readonly message: string;
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

export const JoinStore = signalStore(
  withState<JoinState>(initial),
  withMethods((store, api = inject(JoinRequestsService)) => {
    const loadMine = rxMethod<void>(
      pipe(
        switchMap(() =>
          api.myJoinRequest().pipe(
            tapResponse({
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
        tap(() => patchState(store, { request: null })),
        switchMap((id) =>
          api.withdraw(id).pipe(
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
