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
  PlanningDayUpdateRequest,
} from '@api/generated/model';
import { mapApiSaveError } from '@shared/util/form';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

// Backend domain-error keys → inline error messages. Mapped via the shared
// `mapApiSaveError` helper (J-5 T-09 extraction) — NOT the per-status `if`
// cascade `errorPatch` we deliberately do not replicate (the low-CRAP rider,
// J-6 Notes / _BOYSCOUT.md).
const SAVE_ERROR_KEYS: Readonly<Record<string, string>> = {
  'planning.day.duplicate': 'A planning day already exists for this date and location.',
};

export type PlanningDayItem = PlanningDayDetail;

interface PlanningExtraState {
  isLoading: boolean;
  loadError: string | null;
  deleteError: string | null;
  // Edit-page state (T-08): the loaded detail to patch the form from, the
  // detail-load spinner, the inline save-error (409 dup / 403 / validation),
  // and the per-day reservations panel (the J-5 read-side join).
  isLoadingDetail: boolean;
  saveError: string | null;
  selectedDetail: PlanningDayDetail | null;
  dayReservations: AircraftReservationListItem[];
  // Picker payloads — list rows decorate the location + 3 crew FK ids from
  // these (cross-module names are NOT server-denormalised, ADR 0023 — the same
  // no-join convention the reservations store follows). The edit-page selects
  // source their options from these too.
  locations: LocationListItem[];
  persons: PersonListItem[];
  locationNameById: Readonly<Record<string, string>>;
  personNameById: Readonly<Record<string, string>>;
  // Aircraft immatriculation map — decorates the inline per-day reservation
  // rows (the J-5 read-side carries only the aircraft FK id).
  immatById: Readonly<Record<string, string>>;
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
};

export const PlanningStore = signalStore(
  { providedIn: 'root' },
  withEntities<PlanningDayItem>(),
  withState<PlanningExtraState>(initialExtra),
  withComputed(({ entities, loadError }) => ({
    isEmpty: computed(() => entities().length === 0),
    hasError: computed(() => loadError() !== null),
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

      // Aircraft immatriculation map — ONLY decorates the inline per-day
      // reservation rows (edit page). Loaded on its OWN best-effort stream so a
      // failed aircraft-picker read can't blank the load-bearing location / crew
      // pickers the create/edit form depends on (those stay in loadDecorations).
      const loadAircraftLabels = rxMethod<void>(
        pipe(
          switchMap(() =>
            aircraftApi.listAircraftForPicker().pipe(
              tapResponse({
                next: (aircraft) => patchState(store, { immatById: buildImmatMap(aircraft) }),
                error: () => {
                  // best-effort — reservation rows fall back to the aircraft id.
                },
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
        // Edit-page: blank create form — no detail, no inline error, empty panel.
        selectNew(): void {
          patchState(store, {
            selectedDetail: null,
            saveError: null,
            dayReservations: [],
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
        // The inline per-day reservations panel (J-5 read-side). No
        // planning-day-scoped reservation endpoint exists, so reuse the J-5
        // `day/{date}` list (overlapping that UTC day) and filter to the day's
        // location client-side (legacy `PlanningDayEditController.js:96-104`).
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
          // Refetch-on-mutation (CLAUDE.md §4b): a planning-day create/update/
          // delete from the edit page or setup wizard refreshes the live list.
          case 'planningDay.created':
          case 'planningDay.updated':
          case 'planningDay.deleted':
            store.loadFuture();
            break;
        }
      });
    },
  }),
);

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
