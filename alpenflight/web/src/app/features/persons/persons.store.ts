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

import { PersonsService } from '@api/generated/persons/persons.service';
import type {
  PersonCreateRequest,
  PersonListItem,
  PersonResponse,
  PersonUpdateRequest,
} from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

export type PersonItem = PersonListItem & { id: string };
export type PersonDetailLoaded = PersonResponse & { id: string };

export type SaveErrorKind = 'forbidden' | 'cross-tenant-blocked' | 'duplicate-membership' | 'other';

interface PersonsExtraState {
  selectedId: string | null;
  selectedDetail: PersonDetailLoaded | null;
  isLoading: boolean;
  isLoadingDetail: boolean;
  loadError: string | null;
  saveError: string | null;
  saveErrorKind: SaveErrorKind | null;
  lastRefreshedAt: number | null;
}

const initialExtra: PersonsExtraState = {
  selectedId: null,
  selectedDetail: null,
  isLoading: false,
  isLoadingDetail: false,
  loadError: null,
  saveError: null,
  saveErrorKind: null,
  lastRefreshedAt: null,
};

function withListItemId(p: PersonListItem): PersonItem {
  if (!p.id) {
    throw new Error('PersonListItem without id — server contract violation');
  }
  return p as PersonItem;
}

function withDetailId(d: PersonResponse): PersonDetailLoaded {
  if (!d.id) {
    throw new Error('PersonResponse without id — server contract violation');
  }
  return d as PersonDetailLoaded;
}

/**
 * Project the detail payload onto the list-row shape for post-save patching.
 * Joined columns (memberStateName, memberNumber, role flags) live on the
 * caller-tenant's PersonClub; on create / update the response includes
 * exactly one PersonClub for the caller's tenant (or zero if the request
 * omitted `initialClubMembership`). The list reload after the mutation
 * settles authoritative values from the JOIN-projection query.
 */
function listItemFromDetail(d: PersonDetailLoaded): PersonItem {
  const pc = d.memberships?.[0];
  const item: PersonItem = {
    id: d.id,
    firstname: d.firstname,
    lastname: d.lastname,
    isActive: pc ? pc.isActive : false,
    isMotorPilot: pc ? pc.isMotorPilot : false,
    isTowPilot: pc ? pc.isTowPilot : false,
    isGliderInstructor: pc ? pc.isGliderInstructor : false,
    isGliderPilot: pc ? pc.isGliderPilot : false,
    isGliderTrainee: pc ? pc.isGliderTrainee : false,
    isWinchOperator: pc ? pc.isWinchOperator : false,
    isMotorInstructor: pc ? pc.isMotorInstructor : false,
  };
  if (d.emailPrivate) item.email = d.emailPrivate;
  if (d.mobilePhone) item.mobilePhone = d.mobilePhone;
  if (d.city) item.city = d.city;
  if (d.zip) item.zip = d.zip;
  if (pc?.memberNumber) item.memberNumber = pc.memberNumber;
  if (pc?.memberStateId) item.memberStateId = pc.memberStateId;
  if (pc?.memberStateName) item.memberStateName = pc.memberStateName;
  return item;
}

export const PersonsStore = signalStore(
  { providedIn: 'root' },
  withEntities<PersonItem>(),
  withState<PersonsExtraState>(initialExtra),
  withComputed(({ entities, loadError, saveError, selectedDetail }) => ({
    isEmpty: computed(() => entities().length === 0),
    hasError: computed(() => loadError() !== null || saveError() !== null),
    selectedPerson: computed(() => selectedDetail()),
  })),
  withMethods((store, personsApi = inject(PersonsService), bus = inject(MUTATION_BUS)) => {
    const loadAll = rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, loadError: null })),
        switchMap(() =>
          personsApi.listPersons().pipe(
            tapResponse({
              next: (items: PersonListItem[]) =>
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
      loadAll,
      loadOne: rxMethod<string>(
        pipe(
          tap(() => patchState(store, { isLoadingDetail: true, saveError: null })),
          switchMap((id) =>
            personsApi.getPerson(id).pipe(
              tapResponse({
                next: (d: PersonResponse) =>
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
      create: rxMethod<PersonCreateRequest>(
        pipe(
          tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
          switchMap((req) =>
            personsApi.createPerson(req).pipe(
              tapResponse({
                next: (d: PersonResponse) => {
                  const detail = withDetailId(d);
                  patchState(store, addEntity(listItemFromDetail(detail)), {
                    selectedDetail: detail,
                  });
                  bus.next({ kind: 'person.created', id: detail.id });
                  loadAll();
                },
                error: (e: HttpErrorResponse) =>
                  patchState(store, errorPatch(e)),
              }),
            ),
          ),
        ),
      ),
      update: rxMethod<{ id: string; req: PersonUpdateRequest }>(
        pipe(
          tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
          switchMap(({ id, req }) =>
            personsApi.updatePerson(id, req).pipe(
              tapResponse({
                next: (d: PersonResponse) => {
                  const detail = withDetailId(d);
                  patchState(store, setEntity(listItemFromDetail(detail)), {
                    selectedDetail: detail,
                  });
                  bus.next({ kind: 'person.updated', id: detail.id });
                  loadAll();
                },
                error: (e: HttpErrorResponse) => patchState(store, errorPatch(e)),
              }),
            ),
          ),
        ),
      ),
      delete: rxMethod<string>(
        pipe(
          tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
          switchMap((id) =>
            personsApi.deletePerson(id).pipe(
              tapResponse({
                next: () => {
                  patchState(store, removeEntity(id), { selectedDetail: null });
                  bus.next({ kind: 'person.deleted', id });
                },
                error: (e: HttpErrorResponse) => patchState(store, errorPatch(e)),
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
          patchState(store, setAllEntities<PersonItem>([]), {
            selectedId: null,
            selectedDetail: null,
            lastRefreshedAt: null,
          });
        }
      });
    },
  }),
);

function errorPatch(e: HttpErrorResponse): { saveError: string; saveErrorKind: SaveErrorKind } {
  if (e.status === 403) {
    return {
      saveError: 'You are not authorized to perform this action on the selected person.',
      saveErrorKind: 'forbidden',
    };
  }
  if (e.status === 409) {
    const body = e.error as { type?: string; detail?: string } | null;
    if (body?.type?.includes('cross-tenant')) {
      return {
        saveError:
          body.detail ?? 'Person has active memberships in other clubs and cannot be deleted from here.',
        saveErrorKind: 'cross-tenant-blocked',
      };
    }
    if (body?.type?.includes('duplicate-membership')) {
      return {
        saveError: body.detail ?? 'Person already has an active membership in this club.',
        saveErrorKind: 'duplicate-membership',
      };
    }
    return {
      saveError: body?.detail ?? 'Conflict — operation refused by the server.',
      saveErrorKind: 'other',
    };
  }
  const body = e.error as { field?: string; detail?: string; message?: string } | null;
  if (body) {
    const msg = body.detail ?? body.message ?? '';
    if (msg.length > 0) {
      return {
        saveError: body.field ? `${body.field}: ${msg}` : msg,
        saveErrorKind: 'other',
      };
    }
  }
  return { saveError: e.message, saveErrorKind: 'other' };
}
