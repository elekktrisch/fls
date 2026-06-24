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
import type { PendingJoinRequestResponse } from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

export type PendingJoinRequestItem = PendingJoinRequestResponse & { id: string };

interface JoinRequestsExtraState {
  isLoading: boolean;
  loadError: string | null;
}

const initialExtra: JoinRequestsExtraState = {
  isLoading: false,
  loadError: null,
};

function withRequestId(r: PendingJoinRequestResponse): PendingJoinRequestItem {
  if (!r.id) {
    throw new Error('PendingJoinRequestResponse without id — server contract violation');
  }
  return r as PendingJoinRequestItem;
}

export const JoinRequestsStore = signalStore(
  { providedIn: 'root' },
  withEntities<PendingJoinRequestItem>(),
  withState<JoinRequestsExtraState>(initialExtra),
  withComputed(({ entities }) => ({
    isEmpty: computed(() => entities().length === 0),
    pendingCount: computed(() => entities().length),
  })),
  withMethods((store, api = inject(JoinRequestsService)) => {
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
    return {
      loadAll,
      removeOne(id: string): void {
        patchState(store, removeEntity(id));
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
