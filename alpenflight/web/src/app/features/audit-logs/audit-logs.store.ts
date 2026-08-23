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
import { filter, pipe, switchMap, tap } from 'rxjs';

import { AuditEventsService } from '@api/generated/audit-events/audit-events.service';
import { UsersService } from '@api/generated/users/users.service';
import type {
  AuditEventPage,
  AuditEventRow,
  ListAuditEventsAction,
  ListAuditEventsParams,
  UserListItem,
} from '@api/generated/model';

import { actorUsernamesByUserId, type ActorUsernamesByUserId } from './list/audit-actor-cell';
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
  actorUsernamesByUserId: ActorUsernamesByUserId;
  actorNameLookupError: string | null;
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
  actorUsernamesByUserId: {},
  actorNameLookupError: null,
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
  withComputed(({ items, loadError, actorNameLookupError, hasMore, pageOffset }) => ({
    isEmpty: computed(() => items().length === 0),
    hasError: computed(() => loadError() !== null),
    actorNamesAreUnresolved: computed(() => actorNameLookupError() !== null),
    canPrev: computed(() => pageOffset() > 0),
    canNext: computed(() => hasMore()),
  })),
  withMethods((store, auditApi = inject(AuditEventsService), usersApi = inject(UsersService)) => {
    const noUsernameIsLoadedYet = (): boolean =>
      Object.keys(store.actorUsernamesByUserId()).length === 0;

    const reportThatEveryActorCellStaysOnItsKeycloakSubFallback = (e: HttpErrorResponse): void =>
      patchState(store, {
        actorUsernamesByUserId: {},
        actorNameLookupError: e.status === 0 ? e.message : `HTTP ${e.status}`,
      });

    const loadActorUsernames = rxMethod<void>(
      pipe(
        filter(noUsernameIsLoadedYet),
        switchMap(() =>
          usersApi.listUsers().pipe(
            tapResponse({
              next: (users: UserListItem[]) =>
                patchState(store, {
                  actorUsernamesByUserId: actorUsernamesByUserId(users),
                  actorNameLookupError: null,
                }),
              error: reportThatEveryActorCellStaysOnItsKeycloakSubFallback,
            }),
          ),
        ),
      ),
    );

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
      loadActorUsernames,
      setFilters(partial: { [K in keyof AuditFilters]?: AuditFilters[K] | undefined }): void {
        const next: AuditFilters = { ...store.filters() };
        for (const [key, value] of Object.entries(partial) as [keyof AuditFilters, unknown][]) {
          if (value === undefined) delete next[key];
          else Object.assign(next, { [key]: value });
        }
        patchState(store, { filters: next, pageOffset: 0 });
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
