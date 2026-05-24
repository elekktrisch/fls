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

import { FlightTypesService } from '@api/generated/flight-types/flight-types.service';
import type {
  FlightTypeCreateRequest,
  FlightTypeDetail,
  FlightTypeListItem,
  FlightTypeUpdateRequest,
} from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

export type FlightTypeItem = FlightTypeListItem & { id: string };
export type FlightTypeDetailLoaded = FlightTypeDetail & { id: string };

export type SaveErrorKind = 'name-duplicate' | 'forbidden' | 'other';

interface FlightTypesExtraState {
  selectedId: string | null;
  selectedDetail: FlightTypeDetailLoaded | null;
  isLoading: boolean;
  isLoadingDetail: boolean;
  loadError: string | null;
  saveError: string | null;
  saveErrorKind: SaveErrorKind | null;
  lastRefreshedAt: number | null;
}

const initialExtra: FlightTypesExtraState = {
  selectedId: null,
  selectedDetail: null,
  isLoading: false,
  isLoadingDetail: false,
  loadError: null,
  saveError: null,
  saveErrorKind: null,
  lastRefreshedAt: null,
};

function withListItemId(ft: FlightTypeListItem): FlightTypeItem {
  if (!ft.id) {
    throw new Error('FlightTypeListItem without id — server contract violation');
  }
  return ft as FlightTypeItem;
}

function withDetailId(d: FlightTypeDetail): FlightTypeDetailLoaded {
  if (!d.id) {
    throw new Error('FlightTypeDetail without id — server contract violation');
  }
  return d as FlightTypeDetailLoaded;
}

/**
 * Project the detail payload onto the list-row shape for optimistic post-save
 * updates. List rows expose the 3 `isFor*Flights` flags + `isFlightCostBalanceSelectable`
 * so the list view can render flag badges without a follow-up GET. The
 * post-mutation `loadAll()` then settles to authoritative values.
 */
function listItemFromDetail(d: FlightTypeDetailLoaded): FlightTypeItem {
  const item: FlightTypeItem = {
    id: d.id,
    flightTypeName: d.flightTypeName,
    isForGliderFlights: d.isForGliderFlights,
    isForTowFlights: d.isForTowFlights,
    isForMotorFlights: d.isForMotorFlights,
    isFlightCostBalanceSelectable: d.isFlightCostBalanceSelectable,
  };
  if (d.flightCode !== undefined) item.flightCode = d.flightCode;
  return item;
}

export const FlightTypesStore = signalStore(
  { providedIn: 'root' },
  withEntities<FlightTypeItem>(),
  withState<FlightTypesExtraState>(initialExtra),
  withComputed(({ entities, loadError, saveError, selectedDetail }) => ({
    isEmpty: computed(() => entities().length === 0),
    hasError: computed(() => loadError() !== null || saveError() !== null),
    selectedFlightType: computed(() => selectedDetail()),
  })),
  withMethods((store, api = inject(FlightTypesService), bus = inject(MUTATION_BUS)) => {
    const loadAll = rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, loadError: null })),
        switchMap(() =>
          api.listFlightTypes().pipe(
            tapResponse({
              next: (items: FlightTypeListItem[]) =>
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
        patchState(store, { selectedId: id, selectedDetail: null });
      },
      clearSaveError(): void {
        patchState(store, { saveError: null, saveErrorKind: null });
      },
      loadAll,
      loadOne: rxMethod<string>(
        pipe(
          tap(() => patchState(store, { isLoadingDetail: true, saveError: null })),
          switchMap((id) =>
            api.getFlightType(id).pipe(
              tapResponse({
                next: (d: FlightTypeDetail) =>
                  patchState(store, {
                    selectedDetail: withDetailId(d),
                    isLoadingDetail: false,
                  }),
                error: (e: HttpErrorResponse) =>
                  patchState(store, { saveError: e.message, isLoadingDetail: false }),
              }),
            ),
          ),
        ),
      ),
      create: rxMethod<FlightTypeCreateRequest>(
        pipe(
          tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
          switchMap((req) =>
            api.registerFlightType(req).pipe(
              tapResponse({
                next: (d: FlightTypeDetail) => {
                  const detail = withDetailId(d);
                  patchState(store, addEntity(listItemFromDetail(detail)), {
                    selectedDetail: detail,
                  });
                  bus.next({ kind: 'flightType.created', id: detail.id });
                  loadAll();
                },
                error: (e: HttpErrorResponse) =>
                  patchState(store, errorPatch(e, req.flightTypeName)),
              }),
            ),
          ),
        ),
      ),
      update: rxMethod<{ id: string; req: FlightTypeUpdateRequest }>(
        pipe(
          tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
          switchMap(({ id, req }) =>
            api.updateFlightType(id, req).pipe(
              tapResponse({
                next: (d: FlightTypeDetail) => {
                  const detail = withDetailId(d);
                  patchState(store, setEntity(listItemFromDetail(detail)), {
                    selectedDetail: detail,
                  });
                  bus.next({ kind: 'flightType.updated', id: detail.id });
                  loadAll();
                },
                error: (e: HttpErrorResponse) =>
                  patchState(store, errorPatch(e, req.flightTypeName)),
              }),
            ),
          ),
        ),
      ),
      delete: rxMethod<string>(
        pipe(
          tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
          switchMap((id) =>
            api.deleteFlightType(id).pipe(
              tapResponse({
                next: () => {
                  patchState(store, removeEntity(id), { selectedDetail: null });
                  bus.next({ kind: 'flightType.deleted', id });
                },
                error: (e: HttpErrorResponse) => patchState(store, errorPatch(e, null)),
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
          patchState(store, setAllEntities<FlightTypeItem>([]), {
            selectedId: null,
            selectedDetail: null,
            lastRefreshedAt: null,
          });
        }
      });
    },
  }),
);

function errorPatch(
  e: HttpErrorResponse,
  name: string | null | undefined,
): { saveError: string; saveErrorKind: SaveErrorKind } {
  if (e.status === 409) {
    return {
      saveError: name
        ? `Flight type "${name}" is already in use.`
        : 'Flight type name is already in use.',
      saveErrorKind: 'name-duplicate',
    };
  }
  if (e.status === 403) {
    return {
      saveError: 'You are not authorized to perform this action on the selected flight type.',
      saveErrorKind: 'forbidden',
    };
  }
  const body = e.error as { field?: string; message?: string } | null;
  if (body && typeof body.message === 'string' && body.message.length > 0) {
    return {
      saveError: body.field ? `${body.field}: ${body.message}` : body.message,
      saveErrorKind: 'other',
    };
  }
  return { saveError: e.message, saveErrorKind: 'other' };
}
