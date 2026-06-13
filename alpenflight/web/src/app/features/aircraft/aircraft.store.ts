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

import { AircraftService } from '@api/generated/aircraft/aircraft.service';
import { ListAircraftType } from '@api/generated/model';
import type {
  AircraftCreateRequest,
  AircraftDetail,
  AircraftListItem,
  AircraftUpdateRequest,
} from '@api/generated/model';

import { classifyApiError, type SaveErrorRule } from '@shared/util/form';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

export type AircraftItem = AircraftListItem & { id: string };
export type AircraftDetailLoaded = AircraftDetail & { id: string };

// `ALL` is a UI-only sentinel — the server defaults to no filter when the
// `type` query param is omitted. The other three values map 1:1 to
// `ListAircraftType` from the generated OpenAPI client and correspond to the
// server-side slice contract in `AircraftTypeSlice.java`.
export type AircraftTypeFilter = 'ALL' | 'GLIDER' | 'TOWING' | 'MOTOR';

export type SaveErrorKind = 'immatriculation-duplicate' | 'forbidden' | 'other';

interface AircraftsExtraState {
  selectedId: string | null;
  selectedDetail: AircraftDetailLoaded | null;
  typeFilter: AircraftTypeFilter;
  isLoading: boolean;
  isLoadingDetail: boolean;
  loadError: string | null;
  saveError: string | null;
  saveErrorKind: SaveErrorKind | null;
  lastRefreshedAt: number | null;
}

const initialExtra: AircraftsExtraState = {
  selectedId: null,
  selectedDetail: null,
  typeFilter: 'ALL',
  isLoading: false,
  isLoadingDetail: false,
  loadError: null,
  saveError: null,
  saveErrorKind: null,
  lastRefreshedAt: null,
};

function withListItemId(a: AircraftListItem): AircraftItem {
  if (!a.id) {
    throw new Error('AircraftListItem without id — server contract violation');
  }
  return a as AircraftItem;
}

function withDetailId(d: AircraftDetail): AircraftDetailLoaded {
  if (!d.id) {
    throw new Error('AircraftDetail without id — server contract violation');
  }
  return d as AircraftDetailLoaded;
}

/**
 * Project the detail payload onto the list-row shape for optimistic post-save
 * updates. Joined columns (`aircraftTypeCode`, `currentStateCode`,
 * `currentStateFlyable`) come from server-side joins, so the optimistic patch
 * omits them — `loadAll()` after the mutation refreshes to authoritative values.
 */
function listItemFromDetail(d: AircraftDetailLoaded): AircraftItem {
  const item: AircraftItem = {
    id: d.id,
    aircraftTypeId: d.aircraftTypeId,
    aircraftTypeCode: '',
    hasEngine: false,
    immatriculation: d.immatriculation,
    isTowingAircraft: d.isTowingAircraft,
  };
  if (d.ownerClubId !== undefined) item.ownerClubId = d.ownerClubId;
  if (d.competitionSign !== undefined) item.competitionSign = d.competitionSign;
  if (d.manufacturerName !== undefined) item.manufacturerName = d.manufacturerName;
  if (d.aircraftModel !== undefined) item.aircraftModel = d.aircraftModel;
  if (d.nrOfSeats !== undefined) item.nrOfSeats = d.nrOfSeats;
  return item;
}

/**
 * Maps the UI-only `AircraftTypeFilter` to the server-side `ListAircraftType`
 * query param. `ALL` returns `null` (no `type=` in the URL — server returns
 * the full set). Membership semantics live on the server in
 * `AircraftTypeSlice.java` (preserves legacy `AircraftService.cs:303-304` for
 * GLIDER and `AircraftService.cs:96` for MOTOR — a glider-with-motor stays in
 * the glider slice, MOTOR excludes glider-with-motor).
 */
function toServerSlice(filter: AircraftTypeFilter): ListAircraftType | null {
  switch (filter) {
    case 'ALL':
      return null;
    case 'GLIDER':
      return ListAircraftType.GLIDER;
    case 'TOWING':
      return ListAircraftType.TOWING;
    case 'MOTOR':
      return ListAircraftType.MOTOR;
  }
}

export const AircraftStore = signalStore(
  { providedIn: 'root' },
  withEntities<AircraftItem>(),
  withState<AircraftsExtraState>(initialExtra),
  withComputed(({ entities, loadError, saveError, selectedDetail }) => ({
    isEmpty: computed(() => entities().length === 0),
    hasError: computed(() => loadError() !== null || saveError() !== null),
    selectedAircraft: computed(() => selectedDetail()),
  })),
  withMethods((store, aircraftsApi = inject(AircraftService), bus = inject(MUTATION_BUS)) => {
    const loadAll = rxMethod<AircraftTypeFilter>(
      pipe(
        tap(() => patchState(store, { isLoading: true, loadError: null })),
        switchMap((filter) => {
          const slice = toServerSlice(filter);
          const params = slice === null ? undefined : { type: slice };
          return aircraftsApi.listAircraft(params).pipe(
            tapResponse({
              next: (items: AircraftListItem[]) =>
                patchState(store, setAllEntities(items.map(withListItemId)), {
                  isLoading: false,
                  lastRefreshedAt: Date.now(),
                }),
              error: (e: HttpErrorResponse) =>
                patchState(store, { loadError: e.message, isLoading: false }),
            }),
          );
        }),
      ),
    );
    return {
      select(id: string | null): void {
        patchState(store, { selectedId: id, selectedDetail: null });
      },
      setTypeFilter(filter: AircraftTypeFilter): void {
        patchState(store, { typeFilter: filter });
        loadAll(filter);
      },
      clearSaveError(): void {
        patchState(store, { saveError: null, saveErrorKind: null });
      },
      loadAll,
      loadOne: rxMethod<string>(
        pipe(
          tap(() => patchState(store, { isLoadingDetail: true, saveError: null })),
          switchMap((id) =>
            aircraftsApi.getAircraft(id).pipe(
              tapResponse({
                next: (d: AircraftDetail) =>
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
      create: rxMethod<AircraftCreateRequest>(
        pipe(
          tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
          switchMap((req) =>
            aircraftsApi.registerAircraft(req).pipe(
              tapResponse({
                next: (d: AircraftDetail) => {
                  const detail = withDetailId(d);
                  patchState(store, addEntity(listItemFromDetail(detail)), {
                    selectedDetail: detail,
                  });
                  bus.next({ kind: 'aircraft.created', aircraftId: detail.id });
                  // Refresh so the joined `aircraftTypeCode` / `hasEngine` /
                  // `currentStateCode` columns settle to authoritative values;
                  // pass the active filter so the post-mutation reload stays
                  // consistent with the user's current slice.
                  loadAll(store.typeFilter());
                },
                error: (e: HttpErrorResponse) =>
                  patchState(store, errorPatch(e, req.immatriculation)),
              }),
            ),
          ),
        ),
      ),
      update: rxMethod<{ id: string; req: AircraftUpdateRequest }>(
        pipe(
          tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
          switchMap(({ id, req }) =>
            aircraftsApi.updateAircraft(id, req).pipe(
              tapResponse({
                next: (d: AircraftDetail) => {
                  const detail = withDetailId(d);
                  patchState(store, setEntity(listItemFromDetail(detail)), {
                    selectedDetail: detail,
                  });
                  bus.next({ kind: 'aircraft.updated', aircraftId: detail.id });
                  loadAll(store.typeFilter());
                },
                error: (e: HttpErrorResponse) =>
                  patchState(store, errorPatch(e, req.immatriculation)),
              }),
            ),
          ),
        ),
      ),
      delete: rxMethod<string>(
        pipe(
          tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
          switchMap((id) =>
            aircraftsApi.deleteAircraft(id).pipe(
              tapResponse({
                next: () => {
                  patchState(store, removeEntity(id), { selectedDetail: null });
                  bus.next({ kind: 'aircraft.deleted', aircraftId: id });
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
      store.loadAll(store.typeFilter());
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        if (evt.kind === 'session.logout' || evt.kind === 'session.tenantSwitch') {
          patchState(store, setAllEntities<AircraftItem>([]), {
            selectedId: null,
            selectedDetail: null,
            lastRefreshedAt: null,
          });
        }
      });
    },
  }),
);

// Ordered classification rules (J-26 T-22, shared `classifyApiError`): 409 →
// immatriculation duplicate (interpolating the submitted registration), 403 →
// forbidden, else a `field`-prefixed validation echo, else the generic tail.
function aircraftErrorRules(
  immatriculation: string | null | undefined,
): readonly SaveErrorRule<SaveErrorKind>[] {
  return [
    {
      status: 409,
      outcome: () => ({
        saveError: immatriculation
          ? `Immatriculation "${immatriculation}" is already in use.`
          : 'Immatriculation is already in use.',
        saveErrorKind: 'immatriculation-duplicate',
      }),
    },
    {
      status: 403,
      outcome: () => ({
        saveError: 'You are not authorized to perform this action on the selected aircraft.',
        saveErrorKind: 'forbidden',
      }),
    },
  ];
}

function errorPatch(
  e: HttpErrorResponse,
  immatriculation: string | null | undefined,
): { saveError: string; saveErrorKind: SaveErrorKind } {
  return classifyApiError(e, aircraftErrorRules(immatriculation), (body, err) => {
    // Preserve the prior tail EXACTLY: a `field`-prefixed validation echo when
    // the body carries a non-empty `message`, else the raw `e.message` (this
    // store never read `detail`, so the generic chain is deliberately not used).
    if (body && typeof body.message === 'string' && body.message.length > 0) {
      return {
        saveError: body.field ? `${body.field}: ${body.message}` : body.message,
        saveErrorKind: 'other',
      };
    }
    return { saveError: err.message, saveErrorKind: 'other' };
  });
}
