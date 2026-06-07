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
  // Inline (date, location) uniqueness pre-check (J-6b T-07). The edit form's
  // STORE owns the `…/validate` call (CLAUDE.md §4 — no HTTP in components);
  // the result surfaces inline on the date field via `liveFieldErrors`'s
  // `asyncErrors$` merge. `uniquenessValidating` drives the pending hint; a
  // `valid:false` result becomes the inline server-error message.
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
    // Inline uniqueness pre-check (T-07): a `valid:false` result becomes the
    // `ValidationErrors` slot the edit form merges onto the date field via
    // `liveFieldErrors`'s `asyncErrors$`. `null` when there is no duplicate (or
    // no probe has run) so the inline message clears.
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
        clearUniquenessValidation(): void {
          patchState(store, { uniquenessValidating: false, uniquenessResult: null });
        },
        // Inline (date, location) uniqueness pre-check (T-07). The edit form
        // fires this (debounced) when planningDate + locationId are set/changed;
        // the SAME J-6 `ux_pln_club_date_loc` uniqueness the save path enforces,
        // behind the non-mutating `…/validate` path (oracle: NO new rule).
        // `excludePlanningDayId` self-excludes on an edit. The result feeds the
        // inline `uniquenessErrors` / `uniquenessMessage` selectors — surfaced
        // on the date field, no save round-trip.
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
                    // A failed probe must not block the form — the save-path 409
                    // stays the backstop. Clear the inline state on error.
                    patchState(store, { uniquenessResult: null, uniquenessValidating: false }),
                }),
              ),
            ),
          ),
        ),
        // Empty the inline per-day reservations panel — used by the edit page
        // when date or location is cleared (the panel keys on date+location,
        // T-08c, so an incomplete key shows nothing rather than stale rows).
        clearDayReservations(): void {
          patchState(store, { dayReservations: [] });
        },
        // Edit-page: blank create form — no detail, no inline error, empty panel.
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
        // Setup wizard (T-09): POST the weekday-expansion rule → the backend
        // expands the range, skips existing days idempotently, bounds the range
        // (T-05). Emits `planningDay.bulkCreated` (count = days actually created;
        // skipped/empty → 0) so the wizard navigates back + the list refetches.
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
          // Refetch-on-mutation (CLAUDE.md §4b): a planning-day create/update/
          // delete from the edit page or setup wizard refreshes the live list.
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

/**
 * Map a `…/validate` uniqueness result to the inline `ValidationErrors` slot the
 * edit form merges onto the date field (T-07). A passing (or absent) result
 * clears the slot; a failing one keys a `duplicate` error so `<af-field-errors>`
 * renders the inline message. Pure — unit-tested without a `TestBed`.
 */
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
