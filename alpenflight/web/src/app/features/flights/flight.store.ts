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

import { FlightsService } from '@api/generated/flights/flights.service';
import type {
  FlightListItem,
  FlightListItemAirState,
  FlightListItemFlightAircraftType,
  ListParams,
} from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

export type FlightRow = FlightListItem;

export interface FlightDateRange {
  readonly from: string | null;
  readonly to: string | null;
}

/**
 * Client-side overlay applied to the loaded page. Server-side filtering is
 * limited to `from/to` on this endpoint (see S-062a "Out of scope — known gaps"),
 * so AirState / ProcessState / FlightAircraftType are narrowed locally over the
 * keyset window until a /flights/search story lands.
 */
export interface FlightClientFilter {
  readonly airStates: readonly FlightListItemAirState[];
  readonly processStateIds: readonly string[];
  readonly aircraftTypes: readonly FlightListItemFlightAircraftType[];
}

const EMPTY_FILTER: FlightClientFilter = {
  airStates: [],
  processStateIds: [],
  aircraftTypes: [],
};

const DEFAULT_LIMIT = 50;

interface FlightExtraState {
  dateFrom: string | null;
  dateTo: string | null;
  clientFilter: FlightClientFilter;
  nextCursor: string | null;
  isLoading: boolean;
  loadError: string | null;
  lastRefreshedAt: number | null;
}

const initial: FlightExtraState = {
  dateFrom: null,
  dateTo: null,
  clientFilter: EMPTY_FILTER,
  nextCursor: null,
  isLoading: false,
  loadError: null,
  lastRefreshedAt: null,
};

function matchesClientFilter(row: FlightListItem, f: FlightClientFilter): boolean {
  if (f.airStates.length > 0 && !f.airStates.includes(row.airState)) {
    return false;
  }
  if (f.processStateIds.length > 0 && !f.processStateIds.includes(row.processStateId)) {
    return false;
  }
  if (f.aircraftTypes.length > 0 && !f.aircraftTypes.includes(row.flightAircraftType)) {
    return false;
  }
  return true;
}

function paramsOf(dateFrom: string | null, dateTo: string | null): ListParams {
  const p: ListParams = { limit: DEFAULT_LIMIT };
  if (dateFrom) p.from = dateFrom;
  if (dateTo) p.to = dateTo;
  return p;
}

export const FlightStore = signalStore(
  { providedIn: 'root' },
  withEntities<FlightRow>(),
  withState<FlightExtraState>(initial),
  withComputed(({ entities, clientFilter, loadError }) => ({
    isEmpty: computed(() => entities().length === 0),
    visibleEntities: computed(() => {
      const f = clientFilter();
      const rows = entities();
      if (f.airStates.length + f.processStateIds.length + f.aircraftTypes.length === 0) {
        return rows;
      }
      return rows.filter((r) => matchesClientFilter(r, f));
    }),
    hasError: computed(() => loadError() !== null),
  })),
  withMethods((store, flightsApi = inject(FlightsService)) => {
    const loadPage = rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, loadError: null })),
        switchMap(() =>
          flightsApi.list(paramsOf(store.dateFrom(), store.dateTo())).pipe(
            tapResponse({
              next: (res) =>
                patchState(store, setAllEntities<FlightRow>(res.items as FlightRow[]), {
                  nextCursor: res.nextCursor ?? null,
                  isLoading: false,
                  lastRefreshedAt: Date.now(),
                }),
              error: (e: HttpErrorResponse) =>
                patchState(store, { loadError: e.message, isLoading: false }),
            }),
          ),
        ),
      ),
    );
    return {
      setDateRange(range: FlightDateRange): void {
        patchState(store, { dateFrom: range.from, dateTo: range.to });
        loadPage();
      },
      setClientFilter(filter: Partial<FlightClientFilter>): void {
        const current = store.clientFilter();
        patchState(store, {
          clientFilter: {
            airStates: filter.airStates ?? current.airStates,
            processStateIds: filter.processStateIds ?? current.processStateIds,
            aircraftTypes: filter.aircraftTypes ?? current.aircraftTypes,
          },
        });
      },
      clearClientFilter(): void {
        patchState(store, { clientFilter: EMPTY_FILTER });
      },
      refresh(): void {
        loadPage();
      },
      clearEntities(): void {
        patchState(store, setAllEntities<FlightRow>([]), { nextCursor: null });
      },
      // exposed so the hook can call without re-creating the rxMethod
      _loadPage: loadPage,
    };
  }),
  withHooks({
    onInit(store) {
      const bus = inject(MUTATION_BUS);
      const destroyRef = inject(DestroyRef);
      store._loadPage();
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        switch (evt.kind) {
          case 'flight.booked':
            store._loadPage();
            return;
          case 'session.tenantSwitch':
            store.clearEntities();
            store._loadPage();
            return;
          case 'session.logout':
            store.clearEntities();
            return;
          default:
            return;
        }
      });
    },
  }),
);
