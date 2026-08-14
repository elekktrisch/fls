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
import {
  addEntity,
  removeEntity,
  setAllEntities,
  setEntity,
  withEntities,
} from '@ngrx/signals/entities';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import { DeliveryCreationTestsService } from '@api/generated/delivery-creation-tests/delivery-creation-tests.service';
import { FlightsService } from '@api/generated/flights/flights.service';
import type {
  DeliveryCreationTestDetail,
  DeliveryCreationTestListItem,
  DeliveryCreationTestWriteRequest,
  ExampleDeliveryResult,
  RunTestResult,
} from '@api/generated/model';

import { classifyApiError, type SaveErrorRule } from '@shared/util/form';

import { MUTATION_BUS } from '../../../core/mutation-bus/mutation-bus';

export type DeliveryCreationTestItem = DeliveryCreationTestListItem & { id: string };
export type DeliveryCreationTestDetailLoaded = DeliveryCreationTestDetail & { id: string };

export interface FlightPickerOption {
  id: string;
  label: string;
}

export type SaveErrorKind = 'forbidden' | 'not-found' | 'other';

interface DeliveryCreationTestsExtraState {
  selectedId: string | null;
  selectedDetail: DeliveryCreationTestDetailLoaded | null;
  exampleResult: ExampleDeliveryResult | null;
  runResult: RunTestResult | null;
  flightOptions: FlightPickerOption[];
  isLoading: boolean;
  isLoadingDetail: boolean;
  isExampleLoading: boolean;
  isRunning: boolean;
  loadError: string | null;
  saveError: string | null;
  saveErrorKind: SaveErrorKind | null;
  notFound: boolean;
  lastRefreshedAt: number | null;
}

const initialExtra: DeliveryCreationTestsExtraState = {
  selectedId: null,
  selectedDetail: null,
  exampleResult: null,
  runResult: null,
  flightOptions: [],
  isLoading: false,
  isLoadingDetail: false,
  isExampleLoading: false,
  isRunning: false,
  loadError: null,
  saveError: null,
  saveErrorKind: null,
  notFound: false,
  lastRefreshedAt: null,
};

function flightOptionLabel(row: Record<string, unknown>): string {
  const parts = [row['flightDate'], row['aircraftImmatriculation'], row['pilotName']]
    .filter((v): v is string => typeof v === 'string' && v.length > 0)
    .map((v) => v);
  return parts.length > 0 ? parts.join(' · ') : String(row['id'] ?? '');
}

function toFlightOptions(payload: unknown): FlightPickerOption[] {
  const rows: unknown[] = Array.isArray(payload)
    ? payload
    : Array.isArray((payload as { items?: unknown }).items)
      ? (payload as { items: unknown[] }).items
      : [];
  return rows
    .filter((r): r is Record<string, unknown> => typeof r === 'object' && r !== null)
    .filter((r) => typeof r['id'] === 'string')
    .map((r) => ({ id: r['id'] as string, label: flightOptionLabel(r) }));
}

function withListItemId(t: DeliveryCreationTestListItem): DeliveryCreationTestItem {
  if (!t.id) {
    throw new Error('DeliveryCreationTestListItem without id — server contract violation');
  }
  return t as DeliveryCreationTestItem;
}

function withDetailId(d: DeliveryCreationTestDetail): DeliveryCreationTestDetailLoaded {
  if (!d.id) {
    throw new Error('DeliveryCreationTestDetail without id — server contract violation');
  }
  return d as DeliveryCreationTestDetailLoaded;
}

function withCapturedExpected(
  req: DeliveryCreationTestWriteRequest,
  example: ExampleDeliveryResult | null,
): DeliveryCreationTestWriteRequest {
  if (!example) return req;
  return {
    ...req,
    expectedDelivery: example.delivery,
    expectedMatchedFilterIds: example.matchedFilterIds,
  };
}

function listItemFromDetail(d: DeliveryCreationTestDetailLoaded): DeliveryCreationTestItem {
  const item: DeliveryCreationTestItem = {
    id: d.id,
    testName: d.testName,
    flightId: d.flightId,
    active: d.active,
  };
  if (d.lastTestSuccessful !== undefined) item.lastTestSuccessful = d.lastTestSuccessful;
  if (d.lastTestRunOn !== undefined) item.lastTestRunOn = d.lastTestRunOn;
  return item;
}

export const DeliveryCreationTestsStore = signalStore(
  { providedIn: 'root' },
  withEntities<DeliveryCreationTestItem>(),
  withState<DeliveryCreationTestsExtraState>(initialExtra),
  withComputed(({ entities, loadError, saveError, selectedDetail }) => ({
    isEmpty: computed(() => entities().length === 0),
    hasError: computed(() => loadError() !== null || saveError() !== null),
    selectedTest: computed(() => selectedDetail()),
  })),
  withMethods(
    (
      store,
      api = inject(DeliveryCreationTestsService),
      flights = inject(FlightsService),
      bus = inject(MUTATION_BUS),
    ) => {
      const loadAll = rxMethod<void>(
        pipe(
          tap(() => patchState(store, { isLoading: true, loadError: null })),
          switchMap(() =>
            api.listDeliveryCreationTest().pipe(
              tapResponse({
                next: (items: DeliveryCreationTestListItem[]) =>
                  patchState(store, setAllEntities(items.map(withListItemId)), {
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
        select(id: string | null): void {
          patchState(store, {
            selectedId: id,
            selectedDetail: null,
            exampleResult: null,
            runResult: null,
            notFound: false,
          });
        },
        clearSaveError(): void {
          patchState(store, { saveError: null, saveErrorKind: null });
        },
        loadAll,
        getDetail: rxMethod<string>(
          pipe(
            tap(() =>
              patchState(store, { isLoadingDetail: true, saveError: null, notFound: false }),
            ),
            switchMap((id) =>
              api.getDeliveryCreationTest(id).pipe(
                tapResponse({
                  next: (d: DeliveryCreationTestDetail) =>
                    patchState(store, {
                      selectedDetail: withDetailId(d),
                      isLoadingDetail: false,
                    }),
                  error: (e: HttpErrorResponse) =>
                    patchState(store, errorPatch(e), {
                      isLoadingDetail: false,
                      notFound: e.status === 404,
                    }),
                }),
              ),
            ),
          ),
        ),
        create: rxMethod<DeliveryCreationTestWriteRequest>(
          pipe(
            tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
            switchMap((req) =>
              api.createDeliveryCreationTest(withCapturedExpected(req, store.exampleResult())).pipe(
                tapResponse({
                  next: (d: DeliveryCreationTestDetail) => {
                    const detail = withDetailId(d);
                    patchState(store, addEntity(listItemFromDetail(detail)), {
                      selectedDetail: detail,
                    });
                    bus.next({ kind: 'delivery-creation-test.created', id: detail.id });
                    loadAll();
                  },
                  error: (e: HttpErrorResponse) => patchState(store, errorPatch(e)),
                }),
              ),
            ),
          ),
        ),
        update: rxMethod<{ id: string; req: DeliveryCreationTestWriteRequest }>(
          pipe(
            tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
            switchMap(({ id, req }) =>
              api
                .updateDeliveryCreationTest(id, withCapturedExpected(req, store.exampleResult()))
                .pipe(
                  tapResponse({
                    next: (d: DeliveryCreationTestDetail) => {
                      const detail = withDetailId(d);
                      patchState(store, setEntity(listItemFromDetail(detail)), {
                        selectedDetail: detail,
                      });
                      bus.next({ kind: 'delivery-creation-test.updated', id: detail.id });
                      loadAll();
                    },
                    error: (e: HttpErrorResponse) => patchState(store, errorPatch(e)),
                  }),
                ),
            ),
          ),
        ),
        delete: rxMethod<string>(
          pipe(
            tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
            switchMap((id) =>
              api.deleteDeliveryCreationTest(id).pipe(
                tapResponse({
                  next: () => {
                    patchState(store, removeEntity(id), { selectedDetail: null });
                    bus.next({ kind: 'delivery-creation-test.deleted', id });
                  },
                  error: (e: HttpErrorResponse) => patchState(store, errorPatch(e)),
                }),
              ),
            ),
          ),
        ),
        exampleForFlight: rxMethod<string>(
          pipe(
            tap(() => patchState(store, { isExampleLoading: true, saveError: null })),
            switchMap((flightId) =>
              api.exampleDeliveryForFlight(flightId).pipe(
                tapResponse({
                  next: (r: ExampleDeliveryResult) =>
                    patchState(store, { exampleResult: r, isExampleLoading: false }),
                  error: (e: HttpErrorResponse) =>
                    patchState(store, errorPatch(e), { isExampleLoading: false }),
                }),
              ),
            ),
          ),
        ),
        run: rxMethod<string>(
          pipe(
            tap(() => patchState(store, { isRunning: true, saveError: null })),
            switchMap((id) =>
              api.runDeliveryCreationTest(id).pipe(
                tapResponse({
                  next: (r: RunTestResult) => patchState(store, { runResult: r, isRunning: false }),
                  error: (e: HttpErrorResponse) =>
                    patchState(store, errorPatch(e), { isRunning: false }),
                }),
              ),
            ),
          ),
        ),
        loadFlightOptions: rxMethod<void>(
          pipe(
            switchMap(() =>
              flights.list().pipe(
                tapResponse({
                  next: (payload) => patchState(store, { flightOptions: toFlightOptions(payload) }),
                  error: () => patchState(store, { flightOptions: [] }),
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
      store.loadAll();
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        if (evt.kind === 'session.logout' || evt.kind === 'session.tenantSwitch') {
          patchState(store, setAllEntities<DeliveryCreationTestItem>([]), {
            selectedId: null,
            selectedDetail: null,
            exampleResult: null,
            runResult: null,
            lastRefreshedAt: null,
          });
        }
      });
    },
  }),
);

const errorRules: readonly SaveErrorRule<SaveErrorKind>[] = [
  {
    status: 403,
    outcome: () => ({
      saveError: 'You are not authorized to manage delivery creation tests.',
      saveErrorKind: 'forbidden',
    }),
  },
  {
    status: 404,
    outcome: () => ({
      saveError: 'This test no longer exists.',
      saveErrorKind: 'not-found',
    }),
  },
];

function errorPatch(e: HttpErrorResponse): { saveError: string; saveErrorKind: SaveErrorKind } {
  return classifyApiError(e, errorRules, (body, err) => {
    if (body && typeof body.message === 'string' && body.message.length > 0) {
      return {
        saveError: body.field ? `${body.field}: ${body.message}` : body.message,
        saveErrorKind: 'other',
      };
    }
    return { saveError: err.message, saveErrorKind: 'other' };
  });
}
