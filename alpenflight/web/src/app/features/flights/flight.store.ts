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
import { firstValueFrom, pipe, switchMap, tap } from 'rxjs';

import { FlightsService } from '@api/generated/flights/flights.service';
import type {
  FlightDetail,
  FlightLastContextResponse,
  FlightListItem,
  FlightListItemAirState,
  FlightListItemFlightAircraftType,
  FlightTemplateResponse,
  ListParams,
} from '@api/generated/model';

import { isoDateFromLocal } from '@shared/util/date';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';
import { buildConflict, type FlightConflict } from './edit/conflict-resolver';
import {
  snapshotToCreateRequests,
  snapshotToUpdateRequest,
  type FlightFormSnapshot,
} from './edit/flight-form.model';

export type FlightRow = FlightListItem;

export interface FlightDateRange {
  readonly from: string | null;
  readonly to: string | null;
}

export interface FlightClientFilter {
  readonly airStates: readonly FlightListItemAirState[];
  readonly processStateIds: readonly string[];
  readonly aircraftTypes: readonly FlightListItemFlightAircraftType[];
}

const EMPTY_FILTER: FlightClientFilter = {
  airStates: [],
  processStateIds: [],
  aircraftTypes: [],
};

const DEFAULT_LIMIT = 50;

interface FlightDetailSlice {
  current: FlightDetail | null;
  currentTow: FlightDetail | null;
  currentVersion: number | null;
  currentTowVersion: number | null;
  detailLoading: boolean;
  detailError: string | null;
  saveError: string | null;
  saveConflict: FlightConflict | null;
  reloadConflict: boolean;
}

export interface OffRangeSavedFlight {
  readonly id: string;
  readonly date: string;
}

interface FlightListSlice {
  dateFrom: string | null;
  dateTo: string | null;
  clientFilter: FlightClientFilter;
  nextCursor: string | null;
  isLoading: boolean;
  loadError: string | null;
  lastRefreshedAt: number | null;
  offRangeSaved: OffRangeSavedFlight | null;
}

type FlightExtraState = FlightListSlice & FlightDetailSlice;

function initialState(): FlightExtraState {
  const today = isoDateFromLocal(new Date());
  return {
    dateFrom: today,
    dateTo: today,
    clientFilter: EMPTY_FILTER,
    nextCursor: null,
    isLoading: false,
    loadError: null,
    lastRefreshedAt: null,
    offRangeSaved: null,
    current: null,
    currentTow: null,
    currentVersion: null,
    currentTowVersion: null,
    detailLoading: false,
    detailError: null,
    saveError: null,
    saveConflict: null,
    reloadConflict: false,
  };
}

function matchesClientFilter(row: FlightListItem, f: FlightClientFilter): boolean {
  if (f.airStates.length > 0 && !f.airStates.includes(row.airState)) {
    return false;
  }
  if (f.processStateIds.length > 0 && !f.processStateIds.includes(row.processStateId)) {
    return false;
  }
  if (f.aircraftTypes.length > 0 && !f.aircraftTypes.includes(row.flightAircraftType)) {
    return false;
  }
  return true;
}

function paramsOf(dateFrom: string | null, dateTo: string | null): ListParams {
  const p: ListParams = { limit: DEFAULT_LIMIT };
  if (dateFrom) p.from = dateFrom;
  if (dateTo) p.to = dateTo;
  return p;
}

export interface UpdatePairVersions {
  glider: number;
  tow: number | null;
}

export const FlightStore = signalStore(
  { providedIn: 'root' },
  withEntities<FlightRow>(),
  withState<FlightExtraState>(initialState()),
  withComputed(
    ({ entities, clientFilter, loadError, saveConflict, reloadConflict, offRangeSaved }) => ({
      isEmpty: computed(() => entities().length === 0),
      visibleEntities: computed(() => {
        const f = clientFilter();
        const rows = entities();
        if (f.airStates.length + f.processStateIds.length + f.aircraftTypes.length === 0) {
          return rows;
        }
        return rows.filter((r) => matchesClientFilter(r, f));
      }),
      hasError: computed(() => loadError() !== null),
      hasSaveConflict: computed(() => saveConflict() !== null),
      hasReloadConflict: computed(() => reloadConflict()),
      hasOffRangeSaved: computed(() => offRangeSaved() !== null),
    }),
  ),
  withMethods((store, flightsApi = inject(FlightsService), bus = inject(MUTATION_BUS)) => {
    const loadPage = rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, loadError: null })),
        switchMap(() =>
          flightsApi.list(paramsOf(store.dateFrom(), store.dateTo())).pipe(
            tapResponse({
              next: (res) =>
                patchState(store, setAllEntities<FlightRow>(res.items as FlightRow[]), {
                  nextCursor: res.nextCursor ?? null,
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

    function isWithinActiveRange(date: string): boolean {
      const from = store.dateFrom();
      const to = store.dateTo();
      return (!from || from <= date) && (!to || to >= date);
    }

    function flagOffRangeSave(id: string | undefined, date: string | null): void {
      if (id && date && !isWithinActiveRange(date)) {
        patchState(store, { offRangeSaved: { id, date } });
      } else {
        patchState(store, { offRangeSaved: null });
      }
    }

    function clearDetail(): void {
      patchState(store, {
        current: null,
        currentTow: null,
        currentVersion: null,
        currentTowVersion: null,
        detailError: null,
        saveError: null,
        saveConflict: null,
        reloadConflict: false,
        offRangeSaved: null,
      });
    }

    async function loadDetail(id: string): Promise<void> {
      patchState(store, { detailLoading: true, detailError: null });
      try {
        const glider = await firstValueFrom(flightsApi.get(id));
        let tow: FlightDetail | null = null;
        if (glider.towFlightId) {
          try {
            tow = await firstValueFrom(flightsApi.get(glider.towFlightId));
          } catch {
          }
        }
        patchState(store, {
          current: glider,
          currentTow: tow,
          currentVersion: glider.version,
          currentTowVersion: tow?.version ?? null,
          detailLoading: false,
        });
      } catch (e) {
        patchState(store, {
          detailLoading: false,
          detailError: (e as HttpErrorResponse).message,
        });
      }
    }

    async function loadNewTemplate(): Promise<FlightTemplateResponse> {
      return firstValueFrom(flightsApi.newTemplate());
    }

    async function loadCopyTemplate(id: string): Promise<FlightTemplateResponse> {
      return firstValueFrom(flightsApi.copyTemplate(id));
    }

    async function loadLastContext(
      aircraftId: string,
      date: string,
    ): Promise<FlightLastContextResponse | null> {
      try {
        return await firstValueFrom(flightsApi.lastContext({ aircraftId, date }));
      } catch {
        return null;
      }
    }

    async function savePair(snapshot: FlightFormSnapshot): Promise<{
      gliderId: string;
      towId?: string;
    }> {
      patchState(store, { saveError: null, saveConflict: null, reloadConflict: false });
      const requests = snapshotToCreateRequests(snapshot);
      const glider = await firstValueFrom(flightsApi.create(requests.glider));
      flagOffRangeSave(glider.id, snapshot.flightDate);
      if (!requests.tow) {
        bus.next({ kind: 'flight.created', flightId: glider.id });
        loadPage();
        return { gliderId: glider.id };
      }
      let tow: FlightDetail;
      try {
        tow = await firstValueFrom(flightsApi.create(requests.tow));
      } catch (e) {
        try {
          await firstValueFrom(flightsApi._delete(glider.id));
        } catch {
        }
        patchState(store, { saveError: (e as HttpErrorResponse).message });
        throw e;
      }
      const linkBody = snapshotToUpdateRequest(snapshot, 'glider', tow.id);
      await firstValueFrom(
        flightsApi.update(glider.id, linkBody, {
          headers: { 'If-Match': String(glider.version) },
        }),
      );
      bus.next({ kind: 'flight.created', flightId: glider.id });
      bus.next({ kind: 'flight.created', flightId: tow.id });
      loadPage();
      return { gliderId: glider.id, towId: tow.id };
    }

    async function updatePair(
      snapshot: FlightFormSnapshot,
      versions: UpdatePairVersions,
    ): Promise<void> {
      const flightId = snapshot.flightId;
      if (!flightId) {
        throw new Error('updatePair: snapshot.flightId is required');
      }
      patchState(store, { saveError: null, saveConflict: null, reloadConflict: false });
      const gliderBody = snapshotToUpdateRequest(snapshot, 'glider');
      try {
        await firstValueFrom(
          flightsApi.update(flightId, gliderBody, {
            headers: { 'If-Match': String(versions.glider) },
          }),
        );
        const currentTow = store.currentTow();
        if (currentTow && versions.tow != null) {
          const towBody = snapshotToUpdateRequest(snapshot, 'tow');
          await firstValueFrom(
            flightsApi.update(currentTow.id, towBody, {
              headers: { 'If-Match': String(versions.tow) },
            }),
          );
          bus.next({ kind: 'flight.updated', flightId: currentTow.id });
        }
        bus.next({ kind: 'flight.updated', flightId });
        flagOffRangeSave(flightId, snapshot.flightDate);
        loadPage();
      } catch (e) {
        const err = e as HttpErrorResponse;
        if (err.status === 412) {
          await openConflict(flightId, gliderBody, err);
        } else if (err.status === 409) {
          patchState(store, { reloadConflict: true, saveError: err.message });
        } else {
          patchState(store, { saveError: err.message });
        }
        throw e;
      }
    }

    async function openConflict(
      flightId: string,
      mine: ReturnType<typeof snapshotToUpdateRequest>,
      err: HttpErrorResponse,
    ): Promise<void> {
      const body = (err.error ?? {}) as { serverVersion?: number };
      let theirs: FlightDetail;
      try {
        theirs = await firstValueFrom(flightsApi.get(flightId));
      } catch {
        patchState(store, { reloadConflict: true, saveError: err.message });
        return;
      }
      const serverVersion =
        typeof body.serverVersion === 'number' ? body.serverVersion : theirs.version;
      patchState(store, {
        saveConflict: buildConflict(flightId, serverVersion, mine, theirs),
        saveError: err.message,
        current: theirs,
        currentVersion: theirs.version,
      });
    }

    function dismissConflict(): void {
      patchState(store, { saveConflict: null, reloadConflict: false });
    }

    async function deleteOne(id: string, ifMatch = '*'): Promise<void> {
      await firstValueFrom(
        flightsApi._delete(id, {
          headers: { 'If-Match': ifMatch },
        }),
      );
      bus.next({ kind: 'flight.deleted', flightId: id });
      loadPage();
    }

    return {
      setDateRange(range: FlightDateRange): void {
        patchState(store, { dateFrom: range.from, dateTo: range.to });
        loadPage();
      },
      viewOffRangeSaved(): void {
        const saved = store.offRangeSaved();
        if (!saved) return;
        patchState(store, {
          dateFrom: saved.date,
          dateTo: saved.date,
          offRangeSaved: null,
        });
        loadPage();
      },
      dismissOffRangeSaved(): void {
        patchState(store, { offRangeSaved: null });
      },
      setClientFilter(filter: Partial<FlightClientFilter>): void {
        const current = store.clientFilter();
        patchState(store, {
          clientFilter: {
            airStates: filter.airStates ?? current.airStates,
            processStateIds: filter.processStateIds ?? current.processStateIds,
            aircraftTypes: filter.aircraftTypes ?? current.aircraftTypes,
          },
        });
      },
      clearClientFilter(): void {
        patchState(store, { clientFilter: EMPTY_FILTER });
      },
      refresh(): void {
        loadPage();
      },
      clearEntities(): void {
        patchState(store, setAllEntities<FlightRow>([]), { nextCursor: null });
      },
      clearDetail,
      dismissConflict,
      loadDetail,
      loadNewTemplate,
      loadCopyTemplate,
      loadLastContext,
      savePair,
      updatePair,
      deleteOne,
      _loadPage: loadPage,
    };
  }),
  withHooks({
    onInit(store) {
      const bus = inject(MUTATION_BUS);
      const destroyRef = inject(DestroyRef);
      store._loadPage();
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        switch (evt.kind) {
          case 'flight.booked':
          case 'flight.created':
          case 'flight.updated':
          case 'flight.deleted':
            store._loadPage();
            return;
          case 'session.tenantSwitch':
            store.clearEntities();
            store.clearDetail();
            store._loadPage();
            return;
          case 'session.logout':
            store.clearEntities();
            store.clearDetail();
            return;
          default:
            return;
        }
      });
    },
  }),
);
