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
import { forkJoin, pipe, switchMap, tap } from 'rxjs';

import { AircraftReservationTypesService } from '@api/generated/aircraft-reservation-types/aircraft-reservation-types.service';
import { AircraftReservationsService } from '@api/generated/aircraft-reservations/aircraft-reservations.service';
import { AircraftService } from '@api/generated/aircraft/aircraft.service';
import { LocationsService } from '@api/generated/locations/locations.service';
import { PersonsService } from '@api/generated/persons/persons.service';
import type {
  AircraftPickerItem,
  AircraftReservationListItem,
  AircraftReservationPage,
  AircraftReservationTypeListItem,
  LocationListItem,
  PersonListItem,
} from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

export type ReservationItem = AircraftReservationListItem & { id: string };

const PAGE_SIZE = 20;

interface ReservationsExtraState {
  pageStart: number;
  totalRows: number;
  isLoading: boolean;
  loadError: string | null;
  saveError: string | null;
  reservationTypes: AircraftReservationTypeListItem[];
  // Client-side label maps — cross-module names are NOT server-denormalised
  // (ADR 0023). Decorate immatriculation / pilot / location from the picker
  // payloads, mirroring the no-cross-module-join convention.
  immatById: Readonly<Record<string, string>>;
  pilotNameById: Readonly<Record<string, string>>;
  locationNameById: Readonly<Record<string, string>>;
}

const initialExtra: ReservationsExtraState = {
  pageStart: 0,
  totalRows: 0,
  isLoading: false,
  loadError: null,
  saveError: null,
  reservationTypes: [],
  immatById: {},
  pilotNameById: {},
  locationNameById: {},
};

function withRowId(r: AircraftReservationListItem): ReservationItem {
  if (!r.id) {
    throw new Error('AircraftReservationListItem without id — server contract violation');
  }
  return r as ReservationItem;
}

export const ReservationsStore = signalStore(
  { providedIn: 'root' },
  withEntities<ReservationItem>(),
  withState<ReservationsExtraState>(initialExtra),
  withComputed(({ entities, loadError, totalRows }) => ({
    isEmpty: computed(() => entities().length === 0),
    hasError: computed(() => loadError() !== null),
    pageSize: computed(() => PAGE_SIZE),
    total: computed(() => totalRows()),
  })),
  withMethods(
    (
      store,
      reservationsApi = inject(AircraftReservationsService),
      reservationTypesApi = inject(AircraftReservationTypesService),
      aircraftApi = inject(AircraftService),
      personsApi = inject(PersonsService),
      locationsApi = inject(LocationsService),
      bus = inject(MUTATION_BUS),
    ) => {
      const loadPage = rxMethod<number>(
        pipe(
          tap(() => patchState(store, { isLoading: true, loadError: null })),
          switchMap((start) =>
            reservationsApi
              .pageAircraftReservations(start, PAGE_SIZE, { sorting: { start: 'asc' } })
              .pipe(
                tapResponse({
                  next: (page: AircraftReservationPage) =>
                    patchState(store, setAllEntities(page.items.map(withRowId)), {
                      pageStart: page.pageStart,
                      totalRows: page.totalRows,
                      isLoading: false,
                    }),
                  error: (e: HttpErrorResponse) =>
                    patchState(store, { loadError: e.message, isLoading: false }),
                }),
              ),
          ),
        ),
      );

      // Cross-module decoration payloads — load once on init / tenant switch.
      const loadDecorations = rxMethod<void>(
        pipe(
          switchMap(() =>
            forkJoin({
              types: reservationTypesApi.listAircraftReservationTypes(),
              aircraft: aircraftApi.listAircraftForPicker(),
              persons: personsApi.listPersons(),
              locations: locationsApi.listLocations(),
            }).pipe(
              tapResponse({
                next: ({ types, aircraft, persons, locations }) =>
                  patchState(store, {
                    reservationTypes: types,
                    immatById: buildImmatMap(aircraft),
                    pilotNameById: buildPilotNameMap(persons),
                    locationNameById: buildLocationNameMap(locations),
                  }),
                error: () => {
                  // Label decoration is best-effort — a failed picker read
                  // leaves the FK ids unlabelled but the list still renders.
                },
              }),
            ),
          ),
        ),
      );

      return {
        loadPage,
        loadDecorations,
        goToPage(page: number): void {
          loadPage((page - 1) * PAGE_SIZE);
        },
        refresh(): void {
          loadPage(store.pageStart());
        },
        clearSaveError(): void {
          patchState(store, { saveError: null });
        },
        delete: rxMethod<string>(
          pipe(
            tap(() => patchState(store, { saveError: null })),
            switchMap((id) =>
              reservationsApi.deleteAircraftReservation(id).pipe(
                tapResponse({
                  next: () => {
                    bus.next({ kind: 'reservation.deleted', reservationId: id });
                    loadPage(store.pageStart());
                  },
                  error: (e: HttpErrorResponse) => patchState(store, { saveError: e.message }),
                }),
              ),
            ),
          ),
        ),
      };
    },
  ),
  withHooks({
    onInit(store) {
      const bus = inject(MUTATION_BUS);
      const destroyRef = inject(DestroyRef);
      store.loadPage(0);
      store.loadDecorations();
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        if (evt.kind === 'session.logout' || evt.kind === 'session.tenantSwitch') {
          patchState(store, setAllEntities<ReservationItem>([]), {
            pageStart: 0,
            totalRows: 0,
          });
          store.loadDecorations();
        }
      });
    },
  }),
);

function buildImmatMap(items: readonly AircraftPickerItem[]): Record<string, string> {
  const map: Record<string, string> = {};
  for (const a of items) map[a.id] = a.immatriculation;
  return map;
}

function buildPilotNameMap(items: readonly PersonListItem[]): Record<string, string> {
  const map: Record<string, string> = {};
  for (const p of items) map[p.id] = `${p.firstname} ${p.lastname}`.trim();
  return map;
}

function buildLocationNameMap(items: readonly LocationListItem[]): Record<string, string> {
  const map: Record<string, string> = {};
  for (const l of items) map[l.id] = l.locationName;
  return map;
}
