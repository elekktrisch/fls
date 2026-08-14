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
import type { ClubDashboardResponse } from '@api/generated/model';

import { MeEventsService } from '../../core/events';
import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

interface ClubDashboardState {
  todaysFlights: number;
  pendingValidation: number;
  isLoading: boolean;
  hasError: boolean;
  hasLoaded: boolean;
}

const initial: ClubDashboardState = {
  todaysFlights: 0,
  pendingValidation: 0,
  isLoading: false,
  hasError: false,
  hasLoaded: false,
};

export const ClubDashboardStore = signalStore(
  { providedIn: 'root' },
  withState<ClubDashboardState>(initial),
  withComputed(({ isLoading, hasError, hasLoaded }) => ({
    showCounts: computed(() => hasLoaded() && !hasError()),
    showLoading: computed(() => isLoading() && !hasLoaded()),
  })),
  withMethods((store, meApi = inject(MeService)) => {
    const load = rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, hasError: false })),
        switchMap(() =>
          meApi.get3().pipe(
            tapResponse({
              next: (counts: ClubDashboardResponse) =>
                patchState(store, {
                  todaysFlights: counts.todaysFlights ?? 0,
                  pendingValidation: counts.pendingValidation ?? 0,
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
      const events = inject(MeEventsService);
      const destroyRef = inject(DestroyRef);

      store.load();

      events
        .on('flight.created')
        .pipe(takeUntilDestroyed(destroyRef))
        .subscribe(() => store.load());

      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        if (evt.kind === 'session.logout' || evt.kind === 'session.tenantSwitch') {
          store.clear();
        }
      });
    },
  }),
);
