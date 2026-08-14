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

import { LocationsService } from '@api/generated/locations/locations.service';
import type {
  LocationCreateRequest,
  LocationDetail,
  LocationListItem,
  LocationUpdateRequest,
} from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

export type LocationItem = LocationListItem & { id: string };
export type LocationDetailLoaded = LocationDetail & { id: string };

export type SaveErrorKind = 'icao-duplicate' | 'other';

interface LocationsExtraState {
  selectedId: string | null;
  selectedDetail: LocationDetailLoaded | null;
  isLoading: boolean;
  isLoadingDetail: boolean;
  loadError: string | null;
  saveError: string | null;
  saveErrorKind: SaveErrorKind | null;
  lastRefreshedAt: number | null;
}

const initialExtra: LocationsExtraState = {
  selectedId: null,
  selectedDetail: null,
  isLoading: false,
  isLoadingDetail: false,
  loadError: null,
  saveError: null,
  saveErrorKind: null,
  lastRefreshedAt: null,
};

function withListItemId(l: LocationListItem): LocationItem {
  if (!l.id) {
    throw new Error('LocationListItem without id — server contract violation');
  }
  return l as LocationItem;
}

function withDetailId(d: LocationDetail): LocationDetailLoaded {
  if (!d.id) {
    throw new Error('LocationDetail without id — server contract violation');
  }
  return d as LocationDetailLoaded;
}

function listItemFromDetail(d: LocationDetailLoaded): LocationItem {
  const item: LocationItem = {
    id: d.id,
    locationName: d.locationName,
    isAirfield: false,
    isFastEntryRecord: d.isFastEntryRecord,
  };
  if (d.locationShortName !== undefined) item.locationShortName = d.locationShortName;
  if (d.icaoCode !== undefined) item.icaoCode = d.icaoCode;
  return item;
}

export const LocationsStore = signalStore(
  { providedIn: 'root' },
  withEntities<LocationItem>(),
  withState<LocationsExtraState>(initialExtra),
  withComputed(({ entities, loadError, saveError, selectedDetail }) => ({
    isEmpty: computed(() => entities().length === 0),
    hasError: computed(() => loadError() !== null || saveError() !== null),
    selectedLocation: computed(() => selectedDetail()),
  })),
  withMethods((store, locationsApi = inject(LocationsService), bus = inject(MUTATION_BUS)) => {
    const loadAll = rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, loadError: null })),
        switchMap(() =>
          locationsApi.listLocations().pipe(
            tapResponse({
              next: (items: LocationListItem[]) =>
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
            locationsApi.getLocation(id).pipe(
              tapResponse({
                next: (d: LocationDetail) =>
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
      create: rxMethod<LocationCreateRequest>(
        pipe(
          tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
          switchMap((req) =>
            locationsApi.createLocation(req).pipe(
              tapResponse({
                next: (d: LocationDetail) => {
                  const detail = withDetailId(d);
                  patchState(store, addEntity(listItemFromDetail(detail)), {
                    selectedDetail: detail,
                  });
                  bus.next({ kind: 'location.created', id: detail.id });
                  loadAll();
                },
                error: (e: HttpErrorResponse) => patchState(store, errorPatch(e, req.icaoCode)),
              }),
            ),
          ),
        ),
      ),
      update: rxMethod<{ id: string; req: LocationUpdateRequest }>(
        pipe(
          tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
          switchMap(({ id, req }) =>
            locationsApi.updateLocation(id, req).pipe(
              tapResponse({
                next: (d: LocationDetail) => {
                  const detail = withDetailId(d);
                  patchState(store, setEntity(listItemFromDetail(detail)), {
                    selectedDetail: detail,
                  });
                  bus.next({ kind: 'location.updated', id: detail.id });
                  loadAll();
                },
                error: (e: HttpErrorResponse) => patchState(store, errorPatch(e, req.icaoCode)),
              }),
            ),
          ),
        ),
      ),
      delete: rxMethod<string>(
        pipe(
          tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
          switchMap((id) =>
            locationsApi.deleteLocation(id).pipe(
              tapResponse({
                next: () => {
                  patchState(store, removeEntity(id), { selectedDetail: null });
                  bus.next({ kind: 'location.deleted', id });
                },
                error: (e: HttpErrorResponse) =>
                  patchState(store, { saveError: e.message, saveErrorKind: 'other' }),
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
          patchState(store, setAllEntities<LocationItem>([]), {
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
  icaoCode: string | null | undefined,
): { saveError: string; saveErrorKind: SaveErrorKind } {
  if (e.status === 409) {
    return {
      saveError: icaoCode
        ? `ICAO code "${icaoCode}" is already in use.`
        : 'ICAO code is already in use.',
      saveErrorKind: 'icao-duplicate',
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
