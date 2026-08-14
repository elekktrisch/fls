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
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import { MeService } from '@api/generated/me/me.service';
import type { SystemDashboardResponse } from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

interface SystemDashboardState {
  totalClubs: number;
  totalUsers: number;
  totalFlights: number;
  isLoading: boolean;
  hasError: boolean;
  hasLoaded: boolean;
}

const initial: SystemDashboardState = {
  totalClubs: 0,
  totalUsers: 0,
  totalFlights: 0,
  isLoading: false,
  hasError: false,
  hasLoaded: false,
};

export const SystemDashboardStore = signalStore(
  { providedIn: 'root' },
  withState<SystemDashboardState>(initial),
  withComputed(({ isLoading, hasError, hasLoaded }) => ({
    showTotals: computed(() => hasLoaded() && !hasError()),
    showLoading: computed(() => isLoading() && !hasLoaded()),
  })),
  withMethods((store, meApi = inject(MeService)) => {
    const load = rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, hasError: false })),
        switchMap(() =>
          meApi.get2().pipe(
            tapResponse({
              next: (totals: SystemDashboardResponse) =>
                patchState(store, {
                  totalClubs: totals.totalClubs ?? 0,
                  totalUsers: totals.totalUsers ?? 0,
                  totalFlights: totals.totalFlights ?? 0,
                  isLoading: false,
                  hasError: false,
                  hasLoaded: true,
                }),
              error: () => patchState(store, { isLoading: false, hasError: true }),
            }),
          ),
        ),
      ),
    );
    return {
      load,
      clear(): void {
        patchState(store, { ...initial });
      },
    };
  }),
  withHooks({
    onInit(store) {
      const bus = inject(MUTATION_BUS);
      const destroyRef = inject(DestroyRef);

      store.load();

      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        if (evt.kind === 'session.logout' || evt.kind === 'session.tenantSwitch') {
          store.clear();
        }
      });
    },
  }),
);
