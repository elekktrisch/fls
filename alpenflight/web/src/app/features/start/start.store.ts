import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
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
import { EMPTY, pipe, switchMap, tap } from 'rxjs';

import { FlightsService } from '@api/generated/flights/flights.service';
import type { FlightDetail, FlightListItem, FlightListResponse } from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

interface StartState {
  lastFlight: FlightDetail | null;
  isLoading: boolean;
  hasError: boolean;
  hasNoFlights: boolean;
  // True once `load(personId)` has been called with a non-null personId; the
  // page distinguishes "still resolving /me" from "resolved, no flights".
  hasAttemptedLoad: boolean;
}

const initial: StartState = {
  lastFlight: null,
  isLoading: false,
  hasError: false,
  hasNoFlights: false,
  hasAttemptedLoad: false,
};

/**
 * Feeds the S-165 home dashboard's "Your last flight" card. Two-step fetch
 * per the refinement (`/me` → `/flights?personId=…&limit=1` → `/flights/{id}`)
 * keeps the list endpoint a single-table projection; the detail call carries
 * the crew + location ids the card resolves through the picker stores.
 *
 * <p>Direct {@link HttpClient} for the list call: the generated client's
 * {@code ListParams} type doesn't carry {@code personId} until {@code
 * openapi.json} is regenerated. TODO(S-165 follow-up): once the snapshot
 * is refreshed and orval re-emits {@code ListParams}, swap to
 * {@code FlightsService.list({personId, limit: 1})} and delete the
 * {@link HttpClient} import.
 */
export const StartStore = signalStore(
  { providedIn: 'root' },
  withState<StartState>(initial),
  withComputed(({ lastFlight, hasNoFlights, hasError, isLoading, hasAttemptedLoad }) => ({
    showEmptyState: computed(
      () => hasAttemptedLoad() && !isLoading() && !hasError() && hasNoFlights(),
    ),
    showLastFlight: computed(() => !isLoading() && !hasError() && lastFlight() !== null),
  })),
  withMethods((store, flightsApi = inject(FlightsService), http = inject(HttpClient)) => {
    const load = rxMethod<string>(
      pipe(
        tap(() =>
          patchState(store, {
            isLoading: true,
            hasError: false,
            hasNoFlights: false,
            hasAttemptedLoad: true,
          }),
        ),
        switchMap((personId) => {
          const params = new HttpParams().set('personId', personId).set('limit', '1');
          return http.get<FlightListResponse>('/api/v1/flights', { params }).pipe(
            switchMap((listRes) => {
              const items = listRes.items ?? [];
              if (items.length === 0) {
                patchState(store, { isLoading: false, hasNoFlights: true, lastFlight: null });
                return EMPTY;
              }
              const top: FlightListItem = items[0]!;
              const id = top.id;
              if (!id) {
                patchState(store, { isLoading: false, hasError: true });
                return EMPTY;
              }
              return flightsApi.get(id).pipe(
                tapResponse({
                  next: (detail: FlightDetail) =>
                    patchState(store, {
                      lastFlight: detail,
                      isLoading: false,
                      hasNoFlights: false,
                    }),
                  error: (_e: HttpErrorResponse) =>
                    patchState(store, { isLoading: false, hasError: true }),
                }),
              );
            }),
          );
        }),
      ),
    );
    return {
      load,
      markNoPersonLink(): void {
        // /me returned personId = null; render the empty state without a
        // flights round-trip. Same visual as "no flights yet" per the AC.
        patchState(store, {
          hasAttemptedLoad: true,
          hasNoFlights: true,
          lastFlight: null,
          isLoading: false,
        });
      },
      clear(): void {
        patchState(store, { ...initial });
      },
    };
  }),
  withHooks({
    onInit(store) {
      const bus = inject(MUTATION_BUS);
      const destroyRef = inject(DestroyRef);
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        if (evt.kind === 'session.logout' || evt.kind === 'session.tenantSwitch') {
          store.clear();
        }
      });
    },
  }),
);
