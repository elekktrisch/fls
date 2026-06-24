import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, computed, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { tapResponse } from '@ngrx/operators';
import {
  patchState,
  signalStore,
  withComputed,
  withHooks,
  withMethods,
  withState,
} from '@ngrx/signals';
import { removeEntity, setAllEntities, withEntities } from '@ngrx/signals/entities';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import { JoinRequestsService } from '@api/generated/join-requests/join-requests.service';
import type {
  ApproveJoinRequest,
  DenyJoinRequest,
  JoinRequestResponse,
  PendingJoinRequestResponse,
} from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

export type PendingJoinRequestItem = PendingJoinRequestResponse & { id: string };

/** What the success toast renders once an approval lands. */
export interface ApprovedToast {
  friendlyName: string;
  clubName: string;
}

export interface ApproveArgs {
  id: string;
  friendlyName: string;
  roles: string[];
  personId?: string;
}

export interface DenyArgs {
  id: string;
  reason?: string;
}

interface JoinRequestsExtraState {
  isLoading: boolean;
  loadError: string | null;
  approveBusy: boolean;
  approveError: string | null;
  approvedToast: ApprovedToast | null;
  denyBusy: boolean;
  denyError: string | null;
}

const initialExtra: JoinRequestsExtraState = {
  isLoading: false,
  loadError: null,
  approveBusy: false,
  approveError: null,
  approvedToast: null,
  denyBusy: false,
  denyError: null,
};

function withRequestId(r: PendingJoinRequestResponse): PendingJoinRequestItem {
  if (!r.id) {
    throw new Error('PendingJoinRequestResponse without id — server contract violation');
  }
  return r as PendingJoinRequestItem;
}

/**
 * The error envelope carries the human reason (already-attached / cross-tenant
 * Person / already-decided) in `detail`; surface it verbatim so the admin reads
 * the cause rather than a generic failure.
 */
function decisionErrorMessage(e: HttpErrorResponse, fallback: string): string {
  const body = (e.error ?? null) as { detail?: string; message?: string } | null;
  return body?.detail ?? body?.message ?? fallback;
}

export const JoinRequestsStore = signalStore(
  { providedIn: 'root' },
  withEntities<PendingJoinRequestItem>(),
  withState<JoinRequestsExtraState>(initialExtra),
  withComputed(({ entities }) => ({
    isEmpty: computed(() => entities().length === 0),
    pendingCount: computed(() => entities().length),
  })),
  withMethods((store, api = inject(JoinRequestsService), bus = inject(MUTATION_BUS)) => {
    const loadAll = rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, loadError: null })),
        switchMap(() =>
          api.listPending({ status: 'pending' }).pipe(
            tapResponse({
              next: (items: PendingJoinRequestResponse[]) =>
                patchState(store, setAllEntities(items.map(withRequestId)), { isLoading: false }),
              error: (e: HttpErrorResponse) =>
                patchState(store, { loadError: e.message, isLoading: false }),
            }),
          ),
        ),
      ),
    );
    const approve = rxMethod<ApproveArgs>(
      pipe(
        tap(() => patchState(store, { approveBusy: true, approveError: null })),
        switchMap((args) => {
          const body: ApproveJoinRequest = { roles: args.roles };
          if (args.personId) {
            body.personId = args.personId;
          }
          return api.approve(args.id, body).pipe(
            tapResponse({
              next: (res: JoinRequestResponse) => {
                patchState(store, removeEntity(args.id), {
                  approveBusy: false,
                  approveError: null,
                  approvedToast: {
                    friendlyName: args.friendlyName,
                    clubName: res.clubName ?? '',
                  },
                });
                bus.next({ kind: 'join-request.decided', id: args.id });
              },
              error: (e: HttpErrorResponse) =>
                patchState(store, {
                  approveBusy: false,
                  approveError: decisionErrorMessage(e, 'Approval failed. Try again.'),
                }),
            }),
          );
        }),
      ),
    );
    const deny = rxMethod<DenyArgs>(
      pipe(
        tap(() => patchState(store, { denyBusy: true, denyError: null })),
        switchMap((args) => {
          const reason = args.reason?.trim();
          const body: DenyJoinRequest = reason ? { reason } : {};
          return api.deny(args.id, body).pipe(
            tapResponse({
              next: () => {
                patchState(store, removeEntity(args.id), { denyBusy: false, denyError: null });
                bus.next({ kind: 'join-request.decided', id: args.id });
              },
              error: (e: HttpErrorResponse) =>
                patchState(store, {
                  denyBusy: false,
                  denyError: decisionErrorMessage(e, 'Denial failed. Try again.'),
                }),
            }),
          );
        }),
      ),
    );
    return {
      loadAll,
      approve,
      deny,
      removeOne(id: string): void {
        patchState(store, removeEntity(id));
      },
      clearApproveError(): void {
        patchState(store, { approveError: null });
      },
      clearDenyError(): void {
        patchState(store, { denyError: null });
      },
      dismissApprovedToast(): void {
        patchState(store, { approvedToast: null });
      },
    };
  }),
  withHooks({
    onInit(store) {
      const bus = inject(MUTATION_BUS);
      const destroyRef = inject(DestroyRef);
      // `clubAdminGuard` on the `/join-requests` route is the structural guard
      // preventing non-admin construction; a future consumer injecting this
      // store outside the guarded route must gate the load itself.
      store.loadAll();
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        if (evt.kind === 'session.logout' || evt.kind === 'session.tenantSwitch') {
          patchState(store, setAllEntities<PendingJoinRequestItem>([]), {
            loadError: null,
          });
        }
      });
    },
  }),
);
