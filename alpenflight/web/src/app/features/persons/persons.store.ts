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
import { of, pipe, switchMap, tap } from 'rxjs';

import { PersonsService } from '@api/generated/persons/persons.service';
import type {
  PersonClubRequest,
  PersonCreateRequest,
  PersonListItem,
  PersonResponse,
  PersonUpdateRequest,
} from '@api/generated/model';

import { classifyApiError, type ProblemDetailBody, type SaveErrorRule } from '@shared/util/form';

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
                error: (e: HttpErrorResponse) => patchState(store, errorPatch(e)),
              }),
            ),
          ),
        ),
      ),
      /**
       * Forked update (J-26 T-04 data-loss fix): the edit form spans TWO
       * resources — Person identity/contact (PUT /persons/{id}) and the
       * caller-tenant PersonClub (PUT /persons/{id}/clubs/current). The two
       * PUTs run SEQUENTIALLY (person first): the membership response then
       * reflects both halves, so it is the authoritative source for the
       * detail/list patch. Any failure — either half — lands in errorPatch
       * and NO `person.updated` event fires, so the edit page stays put and
       * shows the error instead of a false success.
       */
      update: rxMethod<{ id: string; req: PersonUpdateRequest; membership?: PersonClubRequest }>(
        pipe(
          tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
          switchMap(({ id, req, membership }) =>
            personsApi.updatePerson(id, req).pipe(
              switchMap((d: PersonResponse) =>
                membership ? personsApi.updateCurrentClubMembership(id, membership) : of(d),
              ),
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

// Ordered classification rules (J-26 T-23, shared `classifyApiError`): 403 →
// forbidden, then the two 409 `type`-URN discriminators (cross-tenant delete
// block / duplicate-membership) ahead of the broad 409 catch-all. Each rule
// keeps its prior hardcoded fallback message verbatim. The generic tail (a
// `field`-prefixed validation echo when the body carries a non-empty
// detail/message, else `e.message`) stays in `fallback` — it covers 400 and
// every other status exactly as the prior cascade's bottom did.
function personErrorRules(): readonly SaveErrorRule<SaveErrorKind>[] {
  const typeOf = (b: ProblemDetailBody) => b.type ?? '';
  return [
    {
      status: 403,
      outcome: () => ({
        saveError: 'You are not authorized to perform this action on the selected person.',
        saveErrorKind: 'forbidden',
      }),
    },
    {
      status: 409,
      when: (b) => typeOf(b).includes('cross-tenant'),
      outcome: (b) => ({
        saveError:
          b.detail ??
          'Person has active memberships in other clubs and cannot be deleted from here.',
        saveErrorKind: 'cross-tenant-blocked',
      }),
    },
    {
      status: 409,
      when: (b) => typeOf(b).includes('duplicate-membership'),
      outcome: (b) => ({
        saveError: b.detail ?? 'Person already has an active membership in this club.',
        saveErrorKind: 'duplicate-membership',
      }),
    },
    {
      status: 409,
      outcome: (b) => ({
        saveError: b.detail ?? 'Conflict — operation refused by the server.',
        saveErrorKind: 'other',
      }),
    },
  ];
}

function errorPatch(e: HttpErrorResponse): { saveError: string; saveErrorKind: SaveErrorKind } {
  return classifyApiError(e, personErrorRules(), (body, err) => {
    // Prior non-409/403 tail EXACTLY: a `field`-prefixed echo when the body
    // carries a non-empty `detail`/`message`, else the raw `e.message`. The
    // empty-`msg` branch falls to `err.message` (NOT `genericSaveErrorMessage`,
    // which would surface an empty `''` detail) to stay byte-identical.
    const msg = body ? (body.detail ?? body.message ?? '') : '';
    if (msg.length > 0) {
      return {
        saveError: body?.field ? `${body.field}: ${msg}` : msg,
        saveErrorKind: 'other',
      };
    }
    return { saveError: err.message, saveErrorKind: 'other' };
  });
}
