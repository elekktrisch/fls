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
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import { FlightCostBalanceTypesService } from '@api/generated/flight-cost-balance-types/flight-cost-balance-types.service';
import type { FlightCostBalanceTypeResponse } from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

/**
 * Cross-tenant reference catalogue store for FlightCostBalanceType (5 V3 seed
 * rows). Cache-long; cleared on session.logout / session.tenantSwitch.
 *
 * <p>Lazy load — call {@link FlightCostBalanceTypesStore.ensureLoaded} the
 * first time the catalogue is read (the bootstrap prefetch hasn't been
 * extended yet — done so the bundle parses with no live dependency on this
 * store from S-048's prefetch graph until the first FCBT consumer ships).
 */
interface FcbtState {
  items: FlightCostBalanceTypeResponse[];
  isLoading: boolean;
  loadError: string | null;
  hasLoaded: boolean;
}

const initial: FcbtState = {
  items: [],
  isLoading: false,
  loadError: null,
  hasLoaded: false,
};

export const FlightCostBalanceTypesStore = signalStore(
  { providedIn: 'root' },
  withState<FcbtState>(initial),
  withComputed(({ items, loadError }) => ({
    isEmpty: computed(() => items().length === 0),
    hasError: computed(() => loadError() !== null),
  })),
  withMethods((store, api = inject(FlightCostBalanceTypesService)) => {
    const loadAll = rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, loadError: null })),
        switchMap(() =>
          api.listFlightCostBalanceTypes().pipe(
            tapResponse({
              next: (items: FlightCostBalanceTypeResponse[]) =>
                patchState(store, { items, isLoading: false, hasLoaded: true }),
              error: (e: HttpErrorResponse) =>
                patchState(store, { loadError: e.message, isLoading: false }),
            }),
          ),
        ),
      ),
    );
    return {
      loadAll,
      ensureLoaded(): void {
        if (!store.hasLoaded() && !store.isLoading()) {
          loadAll();
        }
      },
    };
  }),
  withHooks({
    onInit(store) {
      const bus = inject(MUTATION_BUS);
      const destroyRef = inject(DestroyRef);
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        if (evt.kind === 'session.logout' || evt.kind === 'session.tenantSwitch') {
          patchState(store, { items: [], hasLoaded: false, loadError: null });
        }
      });
    },
  }),
);
