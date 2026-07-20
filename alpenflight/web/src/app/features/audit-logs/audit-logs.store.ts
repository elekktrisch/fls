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

import { AuditEventsService } from '@api/generated/audit-events/audit-events.service';
import type {
  AuditEventPage,
  AuditEventRow,
  ListAuditEventsAction,
  ListAuditEventsParams,
} from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

const PAGE_SIZE = 50;

export interface AuditFilters {
  action?: ListAuditEventsAction;
  targetEntityType?: string;
  occurredFrom?: string;
  occurredTo?: string;
}

interface AuditLogsState {
  filters: AuditFilters;
  pageOffset: number;
  pageSize: number;
  items: readonly AuditEventRow[];
  hasMore: boolean;
  nextOffset: number;
  isLoading: boolean;
  loadError: string | null;
}

const initialState: AuditLogsState = {
  filters: {},
  pageOffset: 0,
  pageSize: PAGE_SIZE,
  items: [],
  hasMore: false,
  nextOffset: 0,
  isLoading: false,
  loadError: null,
};

function queryParams(
  filters: AuditFilters,
  pageOffset: number,
  pageSize: number,
): ListAuditEventsParams {
  const params: ListAuditEventsParams = { pageSize, pageOffset };
  if (filters.action) params.action = filters.action;
  if (filters.targetEntityType) params.targetEntityType = filters.targetEntityType;
  if (filters.occurredFrom) params.occurredFrom = filters.occurredFrom;
  if (filters.occurredTo) params.occurredTo = filters.occurredTo;
  return params;
}

export const AuditLogsStore = signalStore(
  { providedIn: 'root' },
  withState<AuditLogsState>(initialState),
  withComputed(({ items, loadError, hasMore, pageOffset }) => ({
    isEmpty: computed(() => items().length === 0),
    hasError: computed(() => loadError() !== null),
    canPrev: computed(() => pageOffset() > 0),
    canNext: computed(() => hasMore()),
  })),
  withMethods((store, auditApi = inject(AuditEventsService)) => {
    const loadPage = rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, loadError: null })),
        switchMap(() =>
          auditApi
            .listAuditEvents(queryParams(store.filters(), store.pageOffset(), store.pageSize()))
            .pipe(
              tapResponse({
                next: (page: AuditEventPage) =>
                  patchState(store, {
                    items: page.items ?? [],
                    hasMore: page.hasMore ?? false,
                    nextOffset: page.nextOffset ?? store.pageOffset(),
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
      setFilters(partial: Partial<AuditFilters>): void {
        patchState(store, { filters: { ...store.filters(), ...partial }, pageOffset: 0 });
        loadPage();
      },
      clearFilters(): void {
        patchState(store, { filters: {}, pageOffset: 0 });
        loadPage();
      },
      nextPage(): void {
        if (!store.hasMore()) return;
        patchState(store, { pageOffset: store.pageOffset() + store.pageSize() });
        loadPage();
      },
      prevPage(): void {
        const prev = Math.max(0, store.pageOffset() - store.pageSize());
        if (prev === store.pageOffset()) return;
        patchState(store, { pageOffset: prev });
        loadPage();
      },
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
