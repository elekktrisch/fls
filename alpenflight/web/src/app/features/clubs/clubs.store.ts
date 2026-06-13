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

/**
 * Which control a save error belongs to — the edit page routes the inline
 * `duplicate` marker by this instead of sniffing the message text
 * (J-26 T-07; mirrors the flight-types `SaveErrorKind` from T-05).
 */
export type ClubSaveErrorKind = 'slug-duplicate' | 'club-key-duplicate' | 'other';

interface ClubsExtraState {
  selectedId: string | null;
  isLoading: boolean;
  loadError: string | null;
  saveError: string | null;
  saveErrorKind: ClubSaveErrorKind | null;
  lastRefreshedAt: number | null;
}

const initialExtra: ClubsExtraState = {
  selectedId: null,
  isLoading: false,
  loadError: null,
  saveError: null,
  saveErrorKind: null,
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
      patchState(store, { saveError: null, saveErrorKind: null });
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
        tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
        switchMap((req) =>
          clubsApi.createClub(req).pipe(
            tapResponse({
              next: (c: ClubResponse) => {
                const club = withId(c);
                patchState(store, addEntity(club));
                bus.next({ kind: 'club.created', id: club.id });
              },
              error: (e: HttpErrorResponse) =>
                patchState(store, errorPatch(e, { slug: req.slug, clubKey: req.clubKey })),
            }),
          ),
        ),
      ),
    ),
    update: rxMethod<{ id: string; req: ClubUpdateRequest }>(
      pipe(
        tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
        switchMap(({ id, req }) =>
          clubsApi.updateClub(id, req).pipe(
            tapResponse({
              next: (c: ClubResponse) => {
                patchState(store, updateEntity({ id, changes: withId(c) }));
                bus.next({ kind: 'club.updated', id });
              },
              error: (e: HttpErrorResponse) => patchState(store, errorPatch(e, { slug: req.slug })),
            }),
          ),
        ),
      ),
    ),
    delete: rxMethod<string>(
      pipe(
        tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
        switchMap((id) =>
          clubsApi.deleteClub(id).pipe(
            tapResponse({
              next: () => {
                patchState(store, removeEntity(id));
                bus.next({ kind: 'club.deleted', id });
              },
              error: (e: HttpErrorResponse) =>
                patchState(store, { saveError: e.message, saveErrorKind: 'other' }),
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

function errorPatch(
  e: HttpErrorResponse,
  req: { slug: string; clubKey?: string },
): { saveError: string; saveErrorKind: ClubSaveErrorKind } {
  if (e.status === 409) {
    // Route by the problem-detail `field` (J-26 T-07): the server
    // discriminates ux_club_key vs ux_club_slug — duplicate club keys no
    // longer collapse onto the slug field. An absent field keeps the
    // slug-duplicate shape (the slug conflict's bare 409 carries no body).
    const field = (e.error as { field?: string } | null)?.field;
    if (field === 'clubKey') {
      return {
        saveError: req.clubKey
          ? `Club key "${req.clubKey}" is already in use.`
          : 'Club key is already in use.',
        saveErrorKind: 'club-key-duplicate',
      };
    }
    return {
      saveError: `Slug "${req.slug}" is already in use.`,
      saveErrorKind: 'slug-duplicate',
    };
  }
  // RFC-7807-ish: the server attaches `{ field, message }` (ApiError) for 400.
  // Prefer it over the raw `HttpErrorResponse.message` boilerplate.
  const body = e.error as { field?: string; message?: string } | null;
  if (body && typeof body.message === 'string' && body.message.length > 0) {
    return {
      saveError: body.field ? `${body.field}: ${body.message}` : body.message,
      saveErrorKind: 'other',
    };
  }
  return { saveError: e.message, saveErrorKind: 'other' };
}
