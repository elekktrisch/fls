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
import type { ValidationErrors } from '@angular/forms';
import { debounceTime, forkJoin, pipe, switchMap, tap } from 'rxjs';

import { AircraftReservationTypesService } from '@api/generated/aircraft-reservation-types/aircraft-reservation-types.service';
import { AircraftReservationsService } from '@api/generated/aircraft-reservations/aircraft-reservations.service';
import { AircraftService } from '@api/generated/aircraft/aircraft.service';
import { LocationsService } from '@api/generated/locations/locations.service';
import { PersonsService } from '@api/generated/persons/persons.service';
import type {
  AircraftPickerItem,
  AircraftReservationCreateRequest,
  AircraftReservationDetail,
  AircraftReservationListItem,
  AircraftReservationPage,
  AircraftReservationTypeListItem,
  AircraftReservationUpdateRequest,
  AircraftReservationValidateRequest,
  LocationListItem,
  PersonListItem,
  ReservationValidationResult,
} from '@api/generated/model';
import { mapApiSaveError } from '@shared/util/form';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

const SAVE_ERROR_KEYS: Readonly<Record<string, string>> = {
  'aircraft.reservation.overlap': 'This aircraft is already reserved for an overlapping period.',
  'aircraft.reservation.duration': 'End must be after start.',
};

export type ReservationItem = AircraftReservationListItem & { id: string };

export interface SchedulerLane {
  aircraftId: string;
  immatriculation: string;
  reservations: ReservationItem[];
}

const PAGE_SIZE = 20;

interface ReservationsExtraState {
  pageStart: number;
  totalRows: number;
  isLoading: boolean;
  isLoadingDetail: boolean;
  loadError: string | null;
  saveError: string | null;
  selectedDetail: AircraftReservationDetail | null;
  reservationTypes: AircraftReservationTypeListItem[];
  aircraftPicker: AircraftPickerItem[];
  persons: PersonListItem[];
  locations: LocationListItem[];
  immatById: Readonly<Record<string, string>>;
  pilotNameById: Readonly<Record<string, string>>;
  locationNameById: Readonly<Record<string, string>>;
  overlapValidating: boolean;
  overlapResult: ReservationValidationResult | null;
}

const initialExtra: ReservationsExtraState = {
  pageStart: 0,
  totalRows: 0,
  isLoading: false,
  isLoadingDetail: false,
  loadError: null,
  saveError: null,
  selectedDetail: null,
  reservationTypes: [],
  aircraftPicker: [],
  persons: [],
  locations: [],
  immatById: {},
  pilotNameById: {},
  locationNameById: {},
  overlapValidating: false,
  overlapResult: null,
};

const keepRowsUnlabelledOnPickerFailure = () => undefined;

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
  withComputed(({ entities, loadError, totalRows, immatById, overlapResult }) => ({
    isEmpty: computed(() => entities().length === 0),
    hasError: computed(() => loadError() !== null),
    pageSize: computed(() => PAGE_SIZE),
    total: computed(() => totalRows()),
    overlapErrors: computed<ValidationErrors | null>(() => overlapResultToErrors(overlapResult())),
    overlapMessage: computed<string | null>(() => {
      const r = overlapResult();
      return r && !r.valid ? (r.message ?? 'aircraft.reservation.overlap') : null;
    }),
    schedulerLanes: computed<SchedulerLane[]>(() => {
      const byAircraft = new Map<string, ReservationItem[]>();
      for (const r of entities()) {
        const bucket = byAircraft.get(r.aircraftId);
        if (bucket) bucket.push(r);
        else byAircraft.set(r.aircraftId, [r]);
      }
      const immat = immatById();
      return [...byAircraft.entries()]
        .map(([aircraftId, reservations]) => ({
          aircraftId,
          immatriculation: immat[aircraftId] ?? aircraftId,
          reservations,
        }))
        .sort((a, b) => a.immatriculation.localeCompare(b.immatriculation));
    }),
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
                    aircraftPicker: aircraft,
                    persons,
                    locations,
                    immatById: buildImmatMap(aircraft),
                    pilotNameById: buildPilotNameMap(persons),
                    locationNameById: buildLocationNameMap(locations),
                  }),
                error: keepRowsUnlabelledOnPickerFailure,
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
        clearOverlapValidation(): void {
          patchState(store, { overlapValidating: false, overlapResult: null });
        },
        validateOverlap: rxMethod<AircraftReservationValidateRequest>(
          pipe(
            debounceTime(200),
            tap(() => patchState(store, { overlapValidating: true })),
            switchMap((req) =>
              reservationsApi.validateAircraftReservationOverlap(req).pipe(
                tapResponse({
                  next: (result: ReservationValidationResult) =>
                    patchState(store, { overlapResult: result, overlapValidating: false }),
                  error: () => patchState(store, { overlapResult: null, overlapValidating: false }),
                }),
              ),
            ),
          ),
        ),
        selectNew(): void {
          patchState(store, {
            selectedDetail: null,
            saveError: null,
            overlapValidating: false,
            overlapResult: null,
          });
        },
        loadDetail: rxMethod<string>(
          pipe(
            tap(() =>
              patchState(store, { isLoadingDetail: true, saveError: null, selectedDetail: null }),
            ),
            switchMap((id) =>
              reservationsApi.getAircraftReservation(id).pipe(
                tapResponse({
                  next: (detail: AircraftReservationDetail) =>
                    patchState(store, { selectedDetail: detail, isLoadingDetail: false }),
                  error: (e: HttpErrorResponse) =>
                    patchState(store, {
                      saveError: mapApiSaveError(e, SAVE_ERROR_KEYS),
                      isLoadingDetail: false,
                    }),
                }),
              ),
            ),
          ),
        ),
        create: rxMethod<AircraftReservationCreateRequest>(
          pipe(
            tap(() => patchState(store, { saveError: null })),
            switchMap((req) =>
              reservationsApi.createAircraftReservation(req).pipe(
                tapResponse({
                  next: (detail: AircraftReservationDetail) => {
                    loadPage(store.pageStart());
                    bus.next({ kind: 'reservation.created', reservationId: detail.id });
                  },
                  error: (e: HttpErrorResponse) =>
                    patchState(store, { saveError: mapApiSaveError(e, SAVE_ERROR_KEYS) }),
                }),
              ),
            ),
          ),
        ),
        update: rxMethod<{ id: string; req: AircraftReservationUpdateRequest }>(
          pipe(
            tap(() => patchState(store, { saveError: null })),
            switchMap(({ id, req }) =>
              reservationsApi.updateAircraftReservation(id, req).pipe(
                tapResponse({
                  next: (detail: AircraftReservationDetail) => {
                    loadPage(store.pageStart());
                    bus.next({ kind: 'reservation.updated', reservationId: detail.id });
                  },
                  error: (e: HttpErrorResponse) =>
                    patchState(store, { saveError: mapApiSaveError(e, SAVE_ERROR_KEYS) }),
                }),
              ),
            ),
          ),
        ),
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
                  error: (e: HttpErrorResponse) =>
                    patchState(store, { saveError: mapApiSaveError(e, SAVE_ERROR_KEYS) }),
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
        switch (evt.kind) {
          case 'session.logout':
          case 'session.tenantSwitch':
            patchState(store, setAllEntities<ReservationItem>([]), {
              pageStart: 0,
              totalRows: 0,
            });
            store.loadDecorations();
            break;
          case 'reservation.created':
          case 'reservation.updated':
          case 'reservation.deleted':
            store.loadPage(store.pageStart());
            break;
        }
      });
    },
  }),
);

export function overlapResultToErrors(
  result: ReservationValidationResult | null | undefined,
): ValidationErrors | null {
  if (!result || result.valid) return null;
  return { overlap: result.message ?? true };
}

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
