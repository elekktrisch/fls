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
import { UsersService } from '@api/generated/users/users.service';
import type {
  PersonLookupMatch,
  PersonLookupRequest,
  UserInviteRequest,
  UserListItem,
  UserResponse,
  UserUpdateRequest,
} from '@api/generated/model';

import {
  classifyApiError,
  genericSaveErrorMessage,
  type ProblemDetailBody,
  type SaveErrorRule,
} from '@shared/util/form';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

export type UserItem = UserListItem & { id: string };
export type UserDetailLoaded = UserResponse & { id: string };

export type SaveErrorKind =
  | 'forbidden'
  | 'conflict-self-delete'
  | 'conflict-last-admin'
  | 'conflict-username-taken'
  | 'role-grant-rejected'
  | 'validation'
  | 'other';

interface UsersExtraState {
  selectedId: string | null;
  selectedDetail: UserDetailLoaded | null;
  isLoading: boolean;
  isLoadingDetail: boolean;
  loadError: string | null;
  saveError: string | null;
  saveErrorKind: SaveErrorKind | null;
  isResendingInvite: boolean;
  lastRefreshedAt: number | null;
  lookupMatches: readonly PersonLookupMatch[];
  lookupBusy: boolean;
  lookupError: string | null;
  lookupSearched: boolean;
}

const initialExtra: UsersExtraState = {
  selectedId: null,
  selectedDetail: null,
  isLoading: false,
  isLoadingDetail: false,
  loadError: null,
  saveError: null,
  saveErrorKind: null,
  isResendingInvite: false,
  lastRefreshedAt: null,
  lookupMatches: [],
  lookupBusy: false,
  lookupError: null,
  lookupSearched: false,
};

function withListItemId(u: UserListItem): UserItem {
  if (!u.id) {
    throw new Error('UserListItem without id — server contract violation');
  }
  return u as UserItem;
}

function withDetailId(d: UserResponse): UserDetailLoaded {
  if (!d.id) {
    throw new Error('UserResponse without id — server contract violation');
  }
  return d as UserDetailLoaded;
}

function listItemFromDetail(d: UserDetailLoaded): UserItem {
  const item: UserItem = {
    id: d.id,
    username: d.username,
    friendlyName: d.friendlyName,
    notificationEmail: d.notificationEmail,
    roles: [...d.roles],
    enabled: d.enabled,
    invitePending: d.invitePending,
  };
  if (d.personId) item.personId = d.personId;
  return item;
}

export const UsersStore = signalStore(
  { providedIn: 'root' },
  withEntities<UserItem>(),
  withState<UsersExtraState>(initialExtra),
  withComputed(({ entities, loadError, saveError, selectedDetail }) => ({
    isEmpty: computed(() => entities().length === 0),
    hasError: computed(() => loadError() !== null || saveError() !== null),
    selectedUser: computed(() => selectedDetail()),
  })),
  withMethods(
    (
      store,
      usersApi = inject(UsersService),
      personsApi = inject(PersonsService),
      bus = inject(MUTATION_BUS),
    ) => {
      const loadAll = rxMethod<void>(
        pipe(
          tap(() => patchState(store, { isLoading: true, loadError: null })),
          switchMap(() =>
            usersApi.listUsers().pipe(
              tapResponse({
                next: (items: UserListItem[]) =>
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
              usersApi.getUser(id).pipe(
                tapResponse({
                  next: (d: UserResponse) =>
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
        invite: rxMethod<UserInviteRequest>(
          pipe(
            tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
            switchMap((req) =>
              usersApi.inviteUser(req).pipe(
                tapResponse({
                  next: (d: UserResponse) => {
                    const detail = withDetailId(d);
                    patchState(store, addEntity(listItemFromDetail(detail)), {
                      selectedDetail: detail,
                    });
                    bus.next({ kind: 'user.created', id: detail.id });
                    loadAll();
                  },
                  error: (e: HttpErrorResponse) => patchState(store, errorPatch(e, 'invite')),
                }),
              ),
            ),
          ),
        ),
        update: rxMethod<{ id: string; req: UserUpdateRequest }>(
          pipe(
            tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
            switchMap(({ id, req }) =>
              usersApi.updateUser(id, req).pipe(
                tapResponse({
                  next: (d: UserResponse) => {
                    const detail = withDetailId(d);
                    patchState(store, setEntity(listItemFromDetail(detail)), {
                      selectedDetail: detail,
                    });
                    bus.next({ kind: 'user.updated', id: detail.id });
                    loadAll();
                  },
                  error: (e: HttpErrorResponse) => patchState(store, errorPatch(e, 'update')),
                }),
              ),
            ),
          ),
        ),
        deactivate: rxMethod<string>(
          pipe(
            tap(() => patchState(store, { saveError: null, saveErrorKind: null })),
            switchMap((id) =>
              usersApi.deleteUser(id).pipe(
                tapResponse({
                  next: () => {
                    patchState(store, removeEntity(id), { selectedDetail: null });
                    bus.next({ kind: 'user.deleted', id });
                  },
                  error: (e: HttpErrorResponse) => patchState(store, errorPatch(e, 'delete')),
                }),
              ),
            ),
          ),
        ),
        resendInvite: rxMethod<string>(
          pipe(
            tap(() =>
              patchState(store, { isResendingInvite: true, saveError: null, saveErrorKind: null }),
            ),
            switchMap((id) =>
              usersApi.resendInvite(id).pipe(
                tapResponse({
                  next: () => patchState(store, { isResendingInvite: false }),
                  error: (e: HttpErrorResponse) =>
                    patchState(store, { isResendingInvite: false, ...errorPatch(e, 'resend') }),
                }),
              ),
            ),
          ),
        ),
        lookupPerson: rxMethod<PersonLookupRequest>(
          pipe(
            tap(() =>
              patchState(store, {
                lookupBusy: true,
                lookupError: null,
                lookupMatches: [],
                lookupSearched: false,
              }),
            ),
            switchMap((req) =>
              personsApi.lookupPerson(req).pipe(
                tapResponse({
                  next: (result) =>
                    patchState(store, {
                      lookupMatches: result.matches ?? [],
                      lookupBusy: false,
                      lookupSearched: true,
                    }),
                  error: (e: HttpErrorResponse) => {
                    const body = (e.error ?? null) as { detail?: string; message?: string } | null;
                    patchState(store, {
                      lookupBusy: false,
                      lookupSearched: true,
                      lookupError: body?.detail ?? body?.message ?? 'Lookup failed.',
                    });
                  },
                }),
              ),
            ),
          ),
        ),
        clearLookup(): void {
          patchState(store, {
            lookupMatches: [],
            lookupBusy: false,
            lookupError: null,
            lookupSearched: false,
          });
        },
      };
    },
  ),
  withHooks({
    onInit(store) {
      const bus = inject(MUTATION_BUS);
      const destroyRef = inject(DestroyRef);
      // Cold-start fetch fires here; `clubAdminGuard` on the `/users` route
      // is the structural guard that prevents non-admin construction. If
      // future code injects this store outside the guarded route, gate the
      // load at that consumer.
      store.loadAll();
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        if (evt.kind === 'session.logout' || evt.kind === 'session.tenantSwitch') {
          patchState(store, setAllEntities<UserItem>([]), {
            selectedId: null,
            selectedDetail: null,
            lastRefreshedAt: null,
          });
        }
      });
    },
  }),
);

type Op = 'invite' | 'update' | 'delete' | 'resend';

// Ordered classification rules (J-26 T-22, shared `classifyApiError`). First
// status+`when` match wins, so the narrow 409 discriminators precede the broad
// 409 / 400 catch-alls. The backend uses a single `user-conflict` URN for
// self-delete, last-admin, and username-taken, so the 409 cases discriminate by
// `detail` substring + the in-flight `op`; if distinct URNs land later the
// substring match still holds.
function userErrorRules(op: Op): readonly SaveErrorRule<SaveErrorKind>[] {
  const detailOf = (b: ProblemDetailBody) => b.detail ?? '';
  return [
    {
      status: 403,
      when: (b) => b.type?.includes('role-grant-rejected') ?? false,
      outcome: (b) => ({
        saveError: b.detail ?? 'Caller may not grant one or more of the requested roles.',
        saveErrorKind: 'role-grant-rejected',
      }),
    },
    {
      status: 403,
      outcome: (b) => ({
        saveError:
          b.detail ?? 'You are not authorized to perform this action on the selected user.',
        saveErrorKind: 'forbidden',
      }),
    },
    {
      status: 409,
      when: (b) => op === 'delete' && /cannot deactivate themselves|self/i.test(detailOf(b)),
      outcome: (b) => ({ saveError: detailOf(b), saveErrorKind: 'conflict-self-delete' }),
    },
    {
      status: 409,
      when: (b) => op === 'delete' && /last CLUB_ADMINISTRATOR|last admin/i.test(detailOf(b)),
      outcome: (b) => ({ saveError: detailOf(b), saveErrorKind: 'conflict-last-admin' }),
    },
    {
      status: 409,
      when: (b) => op === 'invite' && /already in use|username/i.test(detailOf(b)),
      outcome: (b) => ({ saveError: detailOf(b), saveErrorKind: 'conflict-username-taken' }),
    },
    {
      status: 409,
      outcome: (b) => ({
        saveError:
          detailOf(b).length > 0 ? detailOf(b) : 'Conflict — operation refused by the server.',
        saveErrorKind: 'other',
      }),
    },
    {
      status: 400,
      outcome: (b, e) => ({
        saveError: genericSaveErrorMessage(b, e),
        saveErrorKind: 'validation',
      }),
    },
  ];
}

function errorPatch(
  e: HttpErrorResponse,
  op: Op,
): { saveError: string; saveErrorKind: SaveErrorKind } {
  return classifyApiError(e, userErrorRules(op), (body, err) => ({
    // The 400 rule already ran its own (no e.message) message; this generic
    // fallback covers every other status, matching the prior cascade's tail.
    saveError: genericSaveErrorMessage(body, err),
    saveErrorKind: 'other',
  }));
}
