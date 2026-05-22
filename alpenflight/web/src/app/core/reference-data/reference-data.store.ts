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

import { AircraftStatesService } from '@api/generated/aircraft-states/aircraft-states.service';
import { AircraftTypesService } from '@api/generated/aircraft-types/aircraft-types.service';
import { ClubStatesService } from '@api/generated/club-states/club-states.service';
import { CountriesService } from '@api/generated/countries/countries.service';
import { LocationTypesService } from '@api/generated/location-types/location-types.service';
import type {
  AircraftStateResponse,
  AircraftTypeResponse,
  ClubStateResponse,
  CountryResponse,
  LocationTypeResponse,
} from '@api/generated/model';

import { MUTATION_BUS } from '../mutation-bus/mutation-bus';

export type Country = CountryResponse & { id: string };
export type ClubState = ClubStateResponse & { id: string };
export type LocationType = LocationTypeResponse & { id: string };
export type AircraftType = AircraftTypeResponse & { id: string };
export type AircraftState = AircraftStateResponse & { id: string };

interface ReferenceDataState {
  countries: readonly Country[];
  clubStates: readonly ClubState[];
  locationTypes: readonly LocationType[];
  aircraftTypes: readonly AircraftType[];
  aircraftStates: readonly AircraftState[];
  isLoading: boolean;
  loadError: string | null;
  lastRefreshedAt: number | null;
}

const initial: ReferenceDataState = {
  countries: [],
  clubStates: [],
  locationTypes: [],
  aircraftTypes: [],
  aircraftStates: [],
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
  withComputed(
    ({ countries, clubStates, locationTypes, aircraftTypes, aircraftStates, lastRefreshedAt }) => ({
      isEmpty: computed(
        () =>
          countries().length === 0 &&
          clubStates().length === 0 &&
          locationTypes().length === 0 &&
          aircraftTypes().length === 0 &&
          aircraftStates().length === 0,
      ),
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
      locationTypeById: computed(() => {
        const map = new Map<string, LocationType>();
        for (const t of locationTypes()) {
          map.set(t.id, t);
        }
        return map;
      }),
      aircraftTypeById: computed(() => {
        const map = new Map<string, AircraftType>();
        for (const t of aircraftTypes()) {
          map.set(t.id, t);
        }
        return map;
      }),
      aircraftStateById: computed(() => {
        const map = new Map<string, AircraftState>();
        for (const s of aircraftStates()) {
          map.set(s.id, s);
        }
        return map;
      }),
      needsRefresh: computed(() => {
        const at = lastRefreshedAt();
        return at === null || Date.now() - at > TTL_MS;
      }),
    }),
  ),
  withMethods(
    (
      store,
      countriesApi = inject(CountriesService),
      clubStatesApi = inject(ClubStatesService),
      locationTypesApi = inject(LocationTypesService),
      aircraftTypesApi = inject(AircraftTypesService),
      aircraftStatesApi = inject(AircraftStatesService),
    ) => ({
      clear(): void {
        patchState(store, initial);
      },
      /**
       * Loads all catalogs in parallel. `catchError` per stream so one slow
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
              locationTypes: locationTypesApi.listLocationTypes().pipe(catchError(() => of(null))),
              aircraftTypes: aircraftTypesApi.listAircraftTypes().pipe(catchError(() => of(null))),
              aircraftStates: aircraftStatesApi
                .listAircraftStates()
                .pipe(catchError(() => of(null))),
            }).pipe(
              tapResponse({
                next: ({ countries, clubStates, locationTypes, aircraftTypes, aircraftStates }) => {
                  patchState(store, {
                    countries: (countries ?? []).map((c) => withId(c, 'CountryResponse')),
                    clubStates: (clubStates ?? []).map((s) => withId(s, 'ClubStateResponse')),
                    locationTypes: (locationTypes ?? []).map((t) =>
                      withId(t, 'LocationTypeResponse'),
                    ),
                    aircraftTypes: (aircraftTypes ?? []).map((t) =>
                      withId(t, 'AircraftTypeResponse'),
                    ),
                    aircraftStates: (aircraftStates ?? []).map((s) =>
                      withId(s, 'AircraftStateResponse'),
                    ),
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
