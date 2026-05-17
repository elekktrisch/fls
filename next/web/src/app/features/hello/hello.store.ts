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

import { HelloService } from '@api/generated/hello/hello.service';
import type { HelloResponse } from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

type Item = HelloResponse;

interface HelloState {
  items: Item[];
  selectedId: string | null;
  isLoading: boolean;
  loadError: string | null;
  saveError: string | null;
  offline: boolean;
  lastRefreshedAt: number | null;
  filter: { query: string };
  pagination: { page: number; pageSize: number; total: number };
}

const initial: HelloState = {
  items: [],
  selectedId: null,
  isLoading: false,
  loadError: null,
  saveError: null,
  offline: false,
  lastRefreshedAt: null,
  filter: { query: '' },
  pagination: { page: 1, pageSize: 20, total: 0 },
};

/**
 * Reference Signal Store. Future domain stores copy this shape.
 *
 * The `/api/v1/hello` endpoint has no id, no list, and no mutation — so
 * `withEntities`, real pagination, and the optimistic-update template are
 * scaffolded as `TODO(S-047)` markers rather than faked against a
 * single-record endpoint.
 *
 * Tied to RxJS-based `HelloService` (not `helloResource()`): the resource
 * is component-scoped, not DI-injectable, and mixing the two in one store
 * risks double-fetches. The HttpClient interceptor (S-021) attaches OIDC
 * tokens to the service-driven path.
 */
export const HelloStore = signalStore(
  { providedIn: 'root' },
  withState(initial),
  withComputed(({ items, loadError, saveError, pagination, filter }) => ({
    isEmpty: computed(() => items().length === 0),
    hasError: computed(() => loadError() !== null || saveError() !== null),
    pageCount: computed(() => Math.max(1, Math.ceil(pagination.total() / pagination.pageSize()))),
    // AC-DIR-3 marker — templates bind via @if (store.showAdvanced()).
    showAdvanced: computed(() => filter.query().length > 0),
  })),
  withMethods((store, helloApi = inject(HelloService)) => ({
    setQuery(query: string): void {
      patchState(store, (s) => ({ filter: { ...s.filter, query } }));
    },
    clear(): void {
      patchState(store, {
        items: [],
        loadError: null,
        saveError: null,
        offline: false,
      });
    },
    loadHello: rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, loadError: null })),
        switchMap(() =>
          helloApi.hello().pipe(
            tapResponse({
              next: (r: HelloResponse) =>
                patchState(store, {
                  items: [r],
                  isLoading: false,
                  offline: false,
                  lastRefreshedAt: Date.now(),
                }),
              error: (e: HttpErrorResponse) => {
                if (e.status === 0) {
                  // AC-DIR-2: network unreachable. S-117 will hydrate from IndexedDB.
                  patchState(store, { offline: true, isLoading: false });
                  return;
                }
                patchState(store, { loadError: e.message, isLoading: false });
              },
            }),
          ),
        ),
      ),
    ),
    /**
     * Optimistic-update template. /api/v1/hello has no mutation; the first
     * real demonstration lands at S-047 (Countries CRUD).
     *
     * Pattern:
     *   1. snapshot prev = items()
     *   2. patchState optimistic
     *   3. rxMethod POST; on error revert to prev + set saveError
     *   4. on success: bus.next({ kind: '<domain>.updated', id })
     */
    markFavorite(): void {
      /* TODO(S-047): wire optimistic update once Countries CRUD lands. */
    },
  })),
  withHooks({
    onInit(store) {
      const bus = inject(MUTATION_BUS);
      const destroyRef = inject(DestroyRef);
      store.loadHello();
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        switch (evt.kind) {
          case 'session.logout':
          case 'session.tenantSwitch':
            store.clear();
            break;
          default:
            break;
        }
      });
    },
  }),
);
