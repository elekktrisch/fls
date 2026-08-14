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
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import { DeliveriesService } from '@api/generated/deliveries/deliveries.service';
import type { DeliveryDetail, DeliveryOverview, DeliveryPage } from '@api/generated/model';
import { mapApiSaveError } from '@shared/util/form';

import { MUTATION_BUS } from '../../../core/mutation-bus/mutation-bus';

const PAGE_SIZE = 20;

const WRITE_ERROR_KEYS: Readonly<Record<string, string>> = {
  'delivery.flight.shared':
    'Diese Lieferung kann nicht gelöscht werden: mehrere Lieferungen teilen sich den Flug.',
  'delivery.booked': 'Verbuchte Lieferungen sind unveränderlich.',
};

interface DeliveriesState {
  rows: DeliveryOverview[];
  totalRows: number;
  pageStart: number;
  selectedDetail: DeliveryDetail | null;
  isLoading: boolean;
  isLoadingDetail: boolean;
  isCreating: boolean;
  loadError: string | null;
  createError: string | null;
  deleteError: string | null;
  notFound: boolean;
}

const initialState: DeliveriesState = {
  rows: [],
  totalRows: 0,
  pageStart: 0,
  selectedDetail: null,
  isLoading: false,
  isLoadingDetail: false,
  isCreating: false,
  loadError: null,
  createError: null,
  deleteError: null,
  notFound: false,
};

export const DeliveriesStore = signalStore(
  { providedIn: 'root' },
  withState<DeliveriesState>(initialState),
  withComputed(({ rows, totalRows }) => ({
    isEmpty: computed(() => rows().length === 0),
    pageSize: computed(() => PAGE_SIZE),
    total: computed(() => totalRows()),
  })),
  withMethods((store, api = inject(DeliveriesService)) => {
    const loadPage = rxMethod<number>(
      pipe(
        tap((start) => patchState(store, { isLoading: true, loadError: null, pageStart: start })),
        switchMap((start) =>
          api.pageDeliveries(start, PAGE_SIZE).pipe(
            tapResponse({
              next: (page: DeliveryPage) =>
                patchState(store, {
                  rows: page.items,
                  totalRows: page.totalRows,
                  pageStart: page.pageStart,
                  isLoading: false,
                }),
              error: (e: HttpErrorResponse) =>
                patchState(store, { loadError: e.message, isLoading: false }),
            }),
          ),
        ),
      ),
    );
    const refresh = (): void => {
      loadPage(store.pageStart());
    };
    return {
      loadPage,
      loadFirstPage(): void {
        loadPage(0);
      },
      clearActionErrors(): void {
        patchState(store, { createError: null, deleteError: null });
      },
      createDeliveries: rxMethod<void>(
        pipe(
          tap(() => patchState(store, { isCreating: true, createError: null })),
          switchMap(() =>
            api.createDeliveries().pipe(
              tapResponse({
                next: () => {
                  patchState(store, { isCreating: false });
                  refresh();
                },
                error: (e: HttpErrorResponse) =>
                  patchState(store, {
                    isCreating: false,
                    createError: mapApiSaveError(e, WRITE_ERROR_KEYS),
                  }),
              }),
            ),
          ),
        ),
      ),
      deleteDelivery: rxMethod<string>(
        pipe(
          tap(() => patchState(store, { deleteError: null })),
          switchMap((id) =>
            api.deleteDelivery(id).pipe(
              tapResponse({
                next: () => refresh(),
                error: (e: HttpErrorResponse) =>
                  patchState(store, { deleteError: mapApiSaveError(e, WRITE_ERROR_KEYS) }),
              }),
            ),
          ),
        ),
      ),
      loadDetail: rxMethod<string>(
        pipe(
          tap(() =>
            patchState(store, { isLoadingDetail: true, selectedDetail: null, notFound: false }),
          ),
          switchMap((id) =>
            api.getDelivery(id).pipe(
              tapResponse({
                next: (d: DeliveryDetail) =>
                  patchState(store, { selectedDetail: d, isLoadingDetail: false }),
                error: (e: HttpErrorResponse) =>
                  patchState(store, { isLoadingDetail: false, notFound: e.status === 404 }),
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
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        if (evt.kind === 'session.logout' || evt.kind === 'session.tenantSwitch') {
          patchState(store, initialState);
        }
      });
    },
  }),
);
