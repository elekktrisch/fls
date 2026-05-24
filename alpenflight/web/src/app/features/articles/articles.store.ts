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

import { ArticlesService } from '@api/generated/articles/articles.service';
import type {
  ArticleCreateRequest,
  ArticleDetail,
  ArticleListItem,
  ArticleUpdateRequest,
} from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

export type ArticleItem = ArticleListItem & { id: string };
export type ArticleDetailLoaded = ArticleDetail & { id: string };

export type SaveErrorKind = 'number-duplicate' | 'forbidden' | 'other';

interface ArticlesExtraState {
  selectedId: string | null;
  selectedDetail: ArticleDetailLoaded | null;
  includeInactive: boolean;
  isLoading: boolean;
  isLoadingDetail: boolean;
  loadError: string | null;
  saveError: string | null;
  saveErrorKind: SaveErrorKind | null;
  lastRefreshedAt: number | null;
}

const initialExtra: ArticlesExtraState = {
  selectedId: null,
  selectedDetail: null,
  includeInactive: false,
  isLoading: false,
  isLoadingDetail: false,
  loadError: null,
  saveError: null,
  saveErrorKind: null,
  lastRefreshedAt: null,
};

function withListItemId(a: ArticleListItem): ArticleItem {
  if (!a.id) {
    throw new Error('ArticleListItem without id — server contract violation');
  }
  return a as ArticleItem;
}

function withDetailId(d: ArticleDetail): ArticleDetailLoaded {
  if (!d.id) {
    throw new Error('ArticleDetail without id — server contract violation');
  }
  return d as ArticleDetailLoaded;
}

/**
 * Project the detail payload onto the list-row shape for optimistic post-save
 * updates. List rows expose number / name / info / isActive; the post-mutation
 * `loadAll()` then settles to authoritative values.
 */
function listItemFromDetail(d: ArticleDetailLoaded): ArticleItem {
  // `articleInfo` on the generated list-item is `?: string` (orval), so under
  // `exactOptionalPropertyTypes` we must conditionally set it rather than
  // unconditionally assigning the possibly-undefined detail field.
  const item: ArticleItem = {
    id: d.id,
    articleNumber: d.articleNumber,
    articleName: d.articleName,
    isActive: d.isActive,
  };
  if (d.articleInfo !== undefined) item.articleInfo = d.articleInfo;
  return item;
}

export const ArticlesStore = signalStore(
  { providedIn: 'root' },
  withEntities<ArticleItem>(),
  withState<ArticlesExtraState>(initialExtra),
  withComputed(({ entities, loadError, saveError, selectedDetail }) => ({
    isEmpty: computed(() => entities().length === 0),
    hasError: computed(() => loadError() !== null || saveError() !== null),
    selectedArticle: computed(() => selectedDetail()),
  })),
  withMethods((store, api = inject(ArticlesService), bus = inject(MUTATION_BUS)) => {
    const loadAll = rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, loadError: null })),
        switchMap(() =>
          api.listArticles({ includeInactive: store.includeInactive() }).pipe(
            tapResponse({
              next: (items: ArticleListItem[]) =>
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
      setIncludeInactive(value: boolean): void {
        patchState(store, { includeInactive: value });
        loadAll();
      },
      loadAll,
      loadOne: rxMethod<string>(
        pipe(
          tap(() => patchState(store, { isLoadingDetail: true, saveError: null })),
          switchMap((id) =>
            api.getArticle(id).pipe(
              tapResponse({
                next: (d: ArticleDetail) =>
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
      create: rxMethod<ArticleCreateRequest>(
        pipe(
          tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
          switchMap((req) =>
            api.registerArticle(req).pipe(
              tapResponse({
                next: (d: ArticleDetail) => {
                  const detail = withDetailId(d);
                  patchState(store, addEntity(listItemFromDetail(detail)), {
                    selectedDetail: detail,
                  });
                  bus.next({ kind: 'article.created', id: detail.id });
                  loadAll();
                },
                error: (e: HttpErrorResponse) =>
                  patchState(store, errorPatch(e, req.articleNumber)),
              }),
            ),
          ),
        ),
      ),
      update: rxMethod<{ id: string; req: ArticleUpdateRequest }>(
        pipe(
          tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
          switchMap(({ id, req }) =>
            api.updateArticle(id, req).pipe(
              tapResponse({
                next: (d: ArticleDetail) => {
                  const detail = withDetailId(d);
                  patchState(store, setEntity(listItemFromDetail(detail)), {
                    selectedDetail: detail,
                  });
                  bus.next({ kind: 'article.updated', id: detail.id });
                  loadAll();
                },
                error: (e: HttpErrorResponse) =>
                  patchState(store, errorPatch(e, req.articleNumber)),
              }),
            ),
          ),
        ),
      ),
      delete: rxMethod<string>(
        pipe(
          tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
          switchMap((id) =>
            api.deleteArticle(id).pipe(
              tapResponse({
                next: () => {
                  patchState(store, removeEntity(id), { selectedDetail: null });
                  bus.next({ kind: 'article.deleted', id });
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
          patchState(store, setAllEntities<ArticleItem>([]), {
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
  number: string | null | undefined,
): { saveError: string; saveErrorKind: SaveErrorKind } {
  if (e.status === 409) {
    return {
      saveError: number
        ? `Article number "${number}" is already in use.`
        : 'Article number is already in use.',
      saveErrorKind: 'number-duplicate',
    };
  }
  if (e.status === 403) {
    return {
      saveError: 'You are not authorized to perform this action on the selected article.',
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
  return { saveError: e.message || 'Save failed. Please retry.', saveErrorKind: 'other' };
}
