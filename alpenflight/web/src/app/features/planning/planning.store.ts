import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, computed, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import type { ValidationErrors } from '@angular/forms';
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
import { debounceTime, forkJoin, pipe, switchMap, tap } from 'rxjs';

import { AircraftReservationsService } from '@api/generated/aircraft-reservations/aircraft-reservations.service';
import { AircraftService } from '@api/generated/aircraft/aircraft.service';
import { LocationsService } from '@api/generated/locations/locations.service';
import { PersonsService } from '@api/generated/persons/persons.service';
import { PlanningDaysService } from '@api/generated/planning-days/planning-days.service';
import type {
  AircraftPickerItem,
  AircraftReservationListItem,
  LocationListItem,
  PersonListItem,
  PlanningDayCreateRequest,
  PlanningDayDetail,
  PlanningDayRuleRequest,
  PlanningDayUpdateRequest,
  PlanningDayValidateRequest,
  PlanningDayValidationResult,
} from '@api/generated/model';
import { mapApiSaveError } from '@shared/util/form';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

const SAVE_ERROR_KEYS: Readonly<Record<string, string>> = {
  'planning.day.duplicate': 'A planning day already exists for this date and location.',
};

export type PlanningDayItem = PlanningDayDetail;

const keepRowsUnlabelledOnPickerFailure = () => undefined;

interface PlanningExtraState {
  isLoading: boolean;
  loadError: string | null;
  deleteError: string | null;
  isLoadingDetail: boolean;
  saveError: string | null;
  selectedDetail: PlanningDayDetail | null;
  dayReservations: AircraftReservationListItem[];
  locations: LocationListItem[];
  persons: PersonListItem[];
  locationNameById: Readonly<Record<string, string>>;
  personNameById: Readonly<Record<string, string>>;
  immatById: Readonly<Record<string, string>>;
  uniquenessValidating: boolean;
  uniquenessResult: PlanningDayValidationResult | null;
}

const initialExtra: PlanningExtraState = {
  isLoading: false,
  loadError: null,
  deleteError: null,
  isLoadingDetail: false,
  saveError: null,
  selectedDetail: null,
  dayReservations: [],
  locations: [],
  persons: [],
  locationNameById: {},
  personNameById: {},
  immatById: {},
  uniquenessValidating: false,
  uniquenessResult: null,
};

export const PlanningStore = signalStore(
  { providedIn: 'root' },
  withEntities<PlanningDayItem>(),
  withState<PlanningExtraState>(initialExtra),
  withComputed(({ entities, loadError, uniquenessResult }) => ({
    isEmpty: computed(() => entities().length === 0),
    hasError: computed(() => loadError() !== null),
    uniquenessErrors: computed<ValidationErrors | null>(() =>
      uniquenessResultToErrors(uniquenessResult()),
    ),
    uniquenessMessage: computed<string | null>(() => {
      const r = uniquenessResult();
      return r && !r.valid ? (r.message ?? 'planning.day.duplicate') : null;
    }),
  })),
  withMethods(
    (
      store,
      planningApi = inject(PlanningDaysService),
      locationsApi = inject(LocationsService),
      personsApi = inject(PersonsService),
      reservationsApi = inject(AircraftReservationsService),
      aircraftApi = inject(AircraftService),
      bus = inject(MUTATION_BUS),
    ) => {
      const loadFuture = rxMethod<void>(
        pipe(
          tap(() => patchState(store, { isLoading: true, loadError: null })),
          switchMap(() =>
            planningApi.listFuturePlanningDays().pipe(
              tapResponse({
                next: (days: PlanningDayDetail[]) =>
                  patchState(store, setAllEntities(days), { isLoading: false }),
                error: (e: HttpErrorResponse) =>
                  patchState(store, { loadError: e.message, isLoading: false }),
              }),
            ),
          ),
        ),
      );

      const loadAircraftLabels = rxMethod<void>(
        pipe(
          switchMap(() =>
            aircraftApi.listAircraftForPicker().pipe(
              tapResponse({
                next: (aircraft) => patchState(store, { immatById: buildImmatMap(aircraft) }),
                error: keepRowsUnlabelledOnPickerFailure,
              }),
            ),
          ),
        ),
      );

      const loadDecorations = rxMethod<void>(
        pipe(
          switchMap(() =>
            forkJoin({
              locations: locationsApi.listLocations(),
              persons: personsApi.listPersons(),
            }).pipe(
              tapResponse({
                next: ({ locations, persons }) => {
                  patchState(store, {
                    locations,
                    persons,
                    locationNameById: buildLocationNameMap(locations),
                    personNameById: buildPersonNameMap(persons),
                  });
                  loadAircraftLabels();
                },
                error: keepRowsUnlabelledOnPickerFailure,
              }),
            ),
          ),
        ),
      );

      return {
        loadFuture,
        loadDecorations,
        refresh(): void {
          loadFuture();
        },
        clearDeleteError(): void {
          patchState(store, { deleteError: null });
        },
        clearSaveError(): void {
          patchState(store, { saveError: null });
        },
        clearUniquenessValidation(): void {
          patchState(store, { uniquenessValidating: false, uniquenessResult: null });
        },
        validateUniqueness: rxMethod<PlanningDayValidateRequest>(
          pipe(
            debounceTime(200),
            tap(() => patchState(store, { uniquenessValidating: true })),
            switchMap((req) =>
              planningApi.validatePlanningDayUniqueness(req).pipe(
                tapResponse({
                  next: (result: PlanningDayValidationResult) =>
                    patchState(store, { uniquenessResult: result, uniquenessValidating: false }),
                  error: () =>
                    patchState(store, { uniquenessResult: null, uniquenessValidating: false }),
                }),
              ),
            ),
          ),
        ),
        clearDayReservations(): void {
          patchState(store, { dayReservations: [] });
        },
        selectNew(): void {
          patchState(store, {
            selectedDetail: null,
            saveError: null,
            dayReservations: [],
            uniquenessValidating: false,
            uniquenessResult: null,
          });
        },
        loadDetail: rxMethod<string>(
          pipe(
            tap(() =>
              patchState(store, { isLoadingDetail: true, saveError: null, selectedDetail: null }),
            ),
            switchMap((id) =>
              planningApi.getPlanningDay(id).pipe(
                tapResponse({
                  next: (detail: PlanningDayDetail) =>
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
        loadDayReservations: rxMethod<{ date: string; locationId: string }>(
          pipe(
            switchMap(({ date, locationId }) =>
              reservationsApi.listAircraftReservationsForDay(date).pipe(
                tapResponse({
                  next: (items: AircraftReservationListItem[]) =>
                    patchState(store, {
                      dayReservations: items.filter((r) => r.locationId === locationId),
                    }),
                  error: () => patchState(store, { dayReservations: [] }),
                }),
              ),
            ),
          ),
        ),
        create: rxMethod<PlanningDayCreateRequest>(
          pipe(
            tap(() => patchState(store, { saveError: null })),
            switchMap((req) =>
              planningApi.createPlanningDay(req).pipe(
                tapResponse({
                  next: (detail: PlanningDayDetail) => {
                    loadFuture();
                    bus.next({ kind: 'planningDay.created', id: detail.id });
                  },
                  error: (e: HttpErrorResponse) =>
                    patchState(store, { saveError: mapApiSaveError(e, SAVE_ERROR_KEYS) }),
                }),
              ),
            ),
          ),
        ),
        bulkCreate: rxMethod<PlanningDayRuleRequest>(
          pipe(
            tap(() => patchState(store, { saveError: null })),
            switchMap((req) =>
              planningApi.bulkCreatePlanningDays(req).pipe(
                tapResponse({
                  next: (created: PlanningDayDetail[]) => {
                    loadFuture();
                    bus.next({ kind: 'planningDay.bulkCreated', count: created.length });
                  },
                  error: (e: HttpErrorResponse) =>
                    patchState(store, { saveError: mapApiSaveError(e, SAVE_ERROR_KEYS) }),
                }),
              ),
            ),
          ),
        ),
        update: rxMethod<{ id: string; req: PlanningDayUpdateRequest }>(
          pipe(
            tap(() => patchState(store, { saveError: null })),
            switchMap(({ id, req }) =>
              planningApi.updatePlanningDay(id, req).pipe(
                tapResponse({
                  next: (detail: PlanningDayDetail) => {
                    loadFuture();
                    bus.next({ kind: 'planningDay.updated', id: detail.id });
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
            tap(() => patchState(store, { deleteError: null })),
            switchMap((id) =>
              planningApi.deletePlanningDay(id).pipe(
                tapResponse({
                  next: () => {
                    bus.next({ kind: 'planningDay.deleted', id });
                    loadFuture();
                  },
                  error: (e: HttpErrorResponse) =>
                    patchState(store, { deleteError: mapApiSaveError(e, SAVE_ERROR_KEYS) }),
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
      store.loadFuture();
      store.loadDecorations();
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        switch (evt.kind) {
          case 'session.logout':
          case 'session.tenantSwitch':
            patchState(store, setAllEntities<PlanningDayItem>([]));
            store.loadDecorations();
            break;
          case 'planningDay.created':
          case 'planningDay.updated':
          case 'planningDay.deleted':
          case 'planningDay.bulkCreated':
            store.loadFuture();
            break;
        }
      });
    },
  }),
);

export function uniquenessResultToErrors(
  result: PlanningDayValidationResult | null | undefined,
): ValidationErrors | null {
  if (!result || result.valid) return null;
  return { duplicate: result.message ?? true };
}

function buildLocationNameMap(items: readonly LocationListItem[]): Record<string, string> {
  const map: Record<string, string> = {};
  for (const l of items) map[l.id] = l.locationName;
  return map;
}

function buildImmatMap(items: readonly AircraftPickerItem[]): Record<string, string> {
  const map: Record<string, string> = {};
  for (const a of items) map[a.id] = a.immatriculation;
  return map;
}

function buildPersonNameMap(items: readonly PersonListItem[]): Record<string, string> {
  const map: Record<string, string> = {};
  for (const p of items) map[p.id] = `${p.firstname} ${p.lastname}`.trim();
  return map;
}
