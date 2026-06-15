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

import { MUTATION_BUS } from '../../../core/mutation-bus/mutation-bus';

const PAGE_SIZE = 20;

interface DeliveriesState {
  rows: DeliveryOverview[];
  totalRows: number;
  pageStart: number;
  selectedDetail: DeliveryDetail | null;
  isLoading: boolean;
  isLoadingDetail: boolean;
  loadError: string | null;
  notFound: boolean;
}

const initialState: DeliveriesState = {
  rows: [],
  totalRows: 0,
  pageStart: 0,
  selectedDetail: null,
  isLoading: false,
  isLoadingDetail: false,
  loadError: null,
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
    return {
      loadPage,
      loadFirstPage(): void {
        loadPage(0);
      },
      // A 404 is the tenant-isolation outcome — the @TenantId finder never
      // returns another club's row, so a cross-tenant id surfaces not-found.
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
