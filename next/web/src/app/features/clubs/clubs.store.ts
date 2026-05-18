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
  updateEntity,
  withEntities,
} from '@ngrx/signals/entities';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';

import { ClubsService } from '@api/generated/clubs/clubs.service';
import type { ClubCreateRequest, ClubResponse, ClubUpdateRequest } from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

/** Generated DTO marks `id` optional; the server always populates it. */
export type Club = ClubResponse & { id: string };

interface ClubsExtraState {
  selectedId: string | null;
  isLoading: boolean;
  loadError: string | null;
  saveError: string | null;
  lastRefreshedAt: number | null;
}

const initialExtra: ClubsExtraState = {
  selectedId: null,
  isLoading: false,
  loadError: null,
  saveError: null,
  lastRefreshedAt: null,
};

function withId(c: ClubResponse): Club {
  if (!c.id) {
    throw new Error('ClubResponse without id — server contract violation');
  }
  return c as Club;
}

export const ClubsStore = signalStore(
  { providedIn: 'root' },
  withEntities<Club>(),
  withState<ClubsExtraState>(initialExtra),
  withComputed(({ entities, loadError, saveError, selectedId, entityMap }) => ({
    isEmpty: computed(() => entities().length === 0),
    hasError: computed(() => loadError() !== null || saveError() !== null),
    selectedClub: computed(() => {
      const id = selectedId();
      return id ? (entityMap()[id] ?? null) : null;
    }),
  })),
  withMethods((store, clubsApi = inject(ClubsService), bus = inject(MUTATION_BUS)) => ({
    select(id: string | null): void {
      patchState(store, { selectedId: id });
    },
    clearSaveError(): void {
      patchState(store, { saveError: null });
    },
    loadAll: rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, loadError: null })),
        switchMap(() =>
          clubsApi.listClubs().pipe(
            tapResponse({
              next: (cs: ClubResponse[]) =>
                patchState(store, setAllEntities(cs.map(withId)), {
                  isLoading: false,
                  lastRefreshedAt: Date.now(),
                }),
              error: (e: HttpErrorResponse) =>
                patchState(store, { loadError: e.message, isLoading: false }),
            }),
          ),
        ),
      ),
    ),
    create: rxMethod<ClubCreateRequest>(
      pipe(
        tap(() => patchState(store, { saveError: null })),
        switchMap((req) =>
          clubsApi.createClub(req).pipe(
            tapResponse({
              next: (c: ClubResponse) => {
                const club = withId(c);
                patchState(store, addEntity(club));
                bus.next({ kind: 'club.created', id: club.id });
              },
              error: (e: HttpErrorResponse) =>
                patchState(store, { saveError: errorMessage(e, req.slug) }),
            }),
          ),
        ),
      ),
    ),
    update: rxMethod<{ id: string; req: ClubUpdateRequest }>(
      pipe(
        tap(() => patchState(store, { saveError: null })),
        switchMap(({ id, req }) =>
          clubsApi.updateClub(id, req).pipe(
            tapResponse({
              next: (c: ClubResponse) => {
                patchState(store, updateEntity({ id, changes: withId(c) }));
                bus.next({ kind: 'club.updated', id });
              },
              error: (e: HttpErrorResponse) =>
                patchState(store, { saveError: errorMessage(e, req.slug) }),
            }),
          ),
        ),
      ),
    ),
    delete: rxMethod<string>(
      pipe(
        tap(() => patchState(store, { saveError: null })),
        switchMap((id) =>
          clubsApi.deleteClub(id).pipe(
            tapResponse({
              next: () => {
                patchState(store, removeEntity(id));
                bus.next({ kind: 'club.deleted', id });
              },
              error: (e: HttpErrorResponse) => patchState(store, { saveError: e.message }),
            }),
          ),
        ),
      ),
    ),
  })),
  withHooks({
    onInit(store) {
      const bus = inject(MUTATION_BUS);
      const destroyRef = inject(DestroyRef);
      store.loadAll();
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        if (evt.kind === 'session.logout' || evt.kind === 'session.tenantSwitch') {
          patchState(store, setAllEntities<Club>([]), { selectedId: null });
        }
      });
    },
  }),
);

function errorMessage(e: HttpErrorResponse, slug: string): string {
  if (e.status === 409) {
    return `Slug "${slug}" is already in use.`;
  }
  return e.message;
}
