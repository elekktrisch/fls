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
import { setAllEntities, withEntities } from '@ngrx/signals/entities';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import { MemberStatesService } from '@api/generated/member-states/member-states.service';
import type { MemberStateListItem } from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

export type MemberStateItem = MemberStateListItem & { id: string };

interface MemberStatesExtraState {
  isLoading: boolean;
  loadError: string | null;
  lastRefreshedAt: number | null;
}

const initialExtra: MemberStatesExtraState = {
  isLoading: false,
  loadError: null,
  lastRefreshedAt: null,
};

function withItemId(m: MemberStateListItem): MemberStateItem {
  if (!m.id) {
    throw new Error('MemberStateListItem without id — server contract violation');
  }
  return m as MemberStateItem;
}

export const MemberStatesStore = signalStore(
  { providedIn: 'root' },
  withEntities<MemberStateItem>(),
  withState<MemberStatesExtraState>(initialExtra),
  withComputed(({ entities, loadError }) => ({
    isEmpty: computed(() => entities().length === 0),
    hasError: computed(() => loadError() !== null),
  })),
  withMethods((store, api = inject(MemberStatesService)) => ({
    loadAll: rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, loadError: null })),
        switchMap(() =>
          api.listMemberStates().pipe(
            tapResponse({
              next: (items: MemberStateListItem[]) =>
                patchState(store, setAllEntities(items.map(withItemId)), {
                  isLoading: false,
                  lastRefreshedAt: Date.now(),
                }),
              error: (e: HttpErrorResponse) =>
                patchState(store, { loadError: e.message, isLoading: false }),
            }),
          ),
        ),
      ),
    ),
  })),
  withHooks({
    onInit(store) {
      const bus = inject(MUTATION_BUS);
      const destroyRef = inject(DestroyRef);
      store.loadAll();
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        if (evt.kind === 'session.logout' || evt.kind === 'session.tenantSwitch') {
          patchState(store, setAllEntities<MemberStateItem>([]), { lastRefreshedAt: null });
        }
      });
    },
  }),
);
