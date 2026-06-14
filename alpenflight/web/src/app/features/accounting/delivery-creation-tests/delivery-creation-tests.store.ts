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

// 403 → the CLUB_ADMINISTRATOR gate (every harness endpoint is admin-gated);
// 404 → a cross-tenant or deleted row (the @TenantId finder never returns
// another club's test). Everything else falls through to the generic tail.
export type SaveErrorKind = 'forbidden' | 'not-found' | 'other';

interface DeliveryCreationTestsExtraState {
  selectedId: string | null;
  selectedDetail: DeliveryCreationTestDetailLoaded | null;
  // The last dry-run output (T-17 fills the expected-item set from it) and the
  // last run verdict (T-17/T-18 render the result + diff). Cleared on a fresh
  // edit-form entry so a stale run never bleeds across tests.
  exampleResult: ExampleDeliveryResult | null;
  runResult: RunTestResult | null;
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

/**
 * Project the detail onto the list-row shape for an optimistic post-save patch;
 * `loadAll()` after the mutation settles `lastTest*` to the authoritative value.
 */
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
  withMethods((store, api = inject(DeliveryCreationTestsService), bus = inject(MUTATION_BUS)) => {
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
          tap(() => patchState(store, { isLoadingDetail: true, saveError: null, notFound: false })),
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
            api.createDeliveryCreationTest(req).pipe(
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
            api.updateDeliveryCreationTest(id, req).pipe(
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
      // Dry-run the engine for the picked flight — fills the expected-item set
      // WITHOUT persisting (T-17's "Create test delivery"). Failure leaves the
      // form usable; the dry-run never blocks save.
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
      // Run the engine vs the stored expectation (T-17 "Run test"); the verdict +
      // diff + matched-rule ids feed T-17's result panel and T-18's diff UI.
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
    };
  }),
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

// 403 → the CLUB_ADMINISTRATOR gate; 404 → cross-tenant / deleted (the
// @TenantId-scoped finder never returns another club's test).
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
