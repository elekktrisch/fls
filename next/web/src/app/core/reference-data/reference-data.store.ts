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
import { forkJoin, of, pipe, switchMap, tap } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { ClubStatesService } from '@api/generated/club-states/club-states.service';
import { CountriesService } from '@api/generated/countries/countries.service';
import type { ClubStateResponse, CountryResponse } from '@api/generated/model';

import { MUTATION_BUS } from '../mutation-bus/mutation-bus';

export type Country = CountryResponse & { id: string };
export type ClubState = ClubStateResponse & { id: string };

interface ReferenceDataState {
  countries: readonly Country[];
  clubStates: readonly ClubState[];
  isLoading: boolean;
  loadError: string | null;
  lastRefreshedAt: number | null;
}

const initial: ReferenceDataState = {
  countries: [],
  clubStates: [],
  isLoading: false,
  loadError: null,
  lastRefreshedAt: null,
};

// Reference rows are Flyway-managed and only change on schema migration —
// the SPA can cache for a full day without seeing drift.
const TTL_MS = 24 * 60 * 60 * 1000;

function withId<T extends { id?: string }>(r: T, label: string): T & { id: string } {
  if (!r.id) {
    throw new Error(`${label} without id — server contract violation`);
  }
  return r as T & { id: string };
}

export const ReferenceDataStore = signalStore(
  { providedIn: 'root' },
  withState<ReferenceDataState>(initial),
  withComputed(({ countries, clubStates, lastRefreshedAt }) => ({
    isEmpty: computed(() => countries().length === 0 && clubStates().length === 0),
    countryById: computed(() => {
      const map = new Map<string, Country>();
      for (const c of countries()) {
        map.set(c.id, c);
      }
      return map;
    }),
    clubStateById: computed(() => {
      const map = new Map<string, ClubState>();
      for (const s of clubStates()) {
        map.set(s.id, s);
      }
      return map;
    }),
    needsRefresh: computed(() => {
      const at = lastRefreshedAt();
      return at === null || Date.now() - at > TTL_MS;
    }),
  })),
  withMethods(
    (
      store,
      countriesApi = inject(CountriesService),
      clubStatesApi = inject(ClubStatesService),
    ) => ({
      clear(): void {
        patchState(store, initial);
      },
      /**
       * Loads both catalogs in parallel. `catchError` per stream so one slow
       * endpoint does not stall the whole bootstrap (S-006 canonical pattern).
       * Idempotent: TTL-gated; consumers can call freely.
       */
      loadAll: rxMethod<void>(
        pipe(
          tap(() => patchState(store, { isLoading: true, loadError: null })),
          switchMap(() =>
            forkJoin({
              countries: countriesApi.listCountries().pipe(catchError(() => of(null))),
              clubStates: clubStatesApi.listClubStates().pipe(catchError(() => of(null))),
            }).pipe(
              tapResponse({
                next: ({ countries, clubStates }) => {
                  patchState(store, {
                    countries: (countries ?? []).map((c) => withId(c, 'CountryResponse')),
                    clubStates: (clubStates ?? []).map((s) => withId(s, 'ClubStateResponse')),
                    isLoading: false,
                    lastRefreshedAt: Date.now(),
                  });
                },
                error: (e: HttpErrorResponse) =>
                  patchState(store, { isLoading: false, loadError: e.message }),
              }),
            ),
          ),
        ),
      ),
    }),
  ),
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
