import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { UsersService } from '@api/generated/users/users.service';
import type {
  UserInviteRequest,
  UserListItem,
  UserResponse,
  UserUpdateRequest,
} from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';
import { UsersStore } from './users.store';
import { mergeManagedRoles } from './role-catalog';

const SELF_USER_ID = 'usr-019e30c3-2c00-7100-8000-000000000001';
const SEED_USER_ID = 'usr-019e30c3-2c00-7100-8000-000000000002';

const LANG_DE = '019e2e15-2c00-77d0-8000-0000000007d0';

const seedItem: UserListItem = {
  id: SEED_USER_ID,
  username: 'h.meier',
  friendlyName: 'Hans Meier',
  notificationEmail: 'hans.meier@example.test',
  roles: ['PILOT'],
  enabled: true,
  invitePending: false,
};

const seedDetail: UserResponse = {
  id: SEED_USER_ID,
  clubId: '019e30c3-2c00-7001-8000-000000000001',
  username: 'h.meier',
  friendlyName: 'Hans Meier',
  notificationEmail: 'hans.meier@example.test',
  languageId: LANG_DE,
  roles: ['PILOT'],
  enabled: true,
  invitePending: false,
};

interface ApiStubs {
  list: () => Observable<UserListItem[]>;
  get: (id: string) => Observable<UserResponse>;
  invite: (req: UserInviteRequest) => Observable<UserResponse>;
  update: (id: string, req: UserUpdateRequest) => Observable<UserResponse>;
  remove: (id: string) => Observable<void>;
  resend: (id: string) => Observable<void>;
}

function usersServiceStub(stubs: Partial<ApiStubs>): UsersService {
  const api = {
    listUsers: ((options?: unknown) => {
      void options;
      return (stubs.list ?? (() => of([])))();
    }) as UsersService['listUsers'],
    getUser: ((id: string, options?: unknown) => {
      void options;
      return (stubs.get ?? ((iid: string) => of({ ...seedDetail, id: iid })))(id);
    }) as UsersService['getUser'],
    inviteUser: ((req: UserInviteRequest, options?: unknown) => {
      void options;
      return (stubs.invite ?? (() => of(seedDetail)))(req);
    }) as UsersService['inviteUser'],
    updateUser: ((id: string, req: UserUpdateRequest, options?: unknown) => {
      void options;
      return (stubs.update ?? ((iid: string) => of({ ...seedDetail, id: iid })))(id, req);
    }) as UsersService['updateUser'],
    deleteUser: ((id: string, options?: unknown) => {
      void options;
      return (stubs.remove ?? (() => of(undefined as unknown as void)))(id);
    }) as UsersService['deleteUser'],
    resendInvite: ((id: string, options?: unknown) => {
      void options;
      return (stubs.resend ?? (() => of(undefined as unknown as void)))(id);
    }) as UsersService['resendInvite'],
  };
  return api as unknown as UsersService;
}

function configure(api: UsersService): Subject<MutationEvent> {
  const bus = new Subject<MutationEvent>();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: bus },
      { provide: UsersService, useValue: api },
    ],
  });
  return bus;
}

describe('UsersStore', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('initialises empty and loads on construct', () => {
    configure(usersServiceStub({ list: () => of([seedItem]) }));
    const store = TestBed.inject(UsersStore);
    expect(store.entities()).toEqual([seedItem]);
    expect(store.isLoading()).toBe(false);
  });

  it('loadAll surfaces an error on HTTP failure', () => {
    const err = new HttpErrorResponse({ status: 500, statusText: 'Server Error' });
    configure(usersServiceStub({ list: () => throwError(() => err) }));
    const store = TestBed.inject(UsersStore);
    expect(store.loadError()).not.toBeNull();
  });

  it('loadOne populates selectedUser', () => {
    configure(usersServiceStub({ list: () => of([seedItem]), get: () => of(seedDetail) }));
    const store = TestBed.inject(UsersStore);
    store.loadOne(SEED_USER_ID);
    expect(store.selectedUser()?.id).toBe(SEED_USER_ID);
  });

  it('invite adds a list entity, sets selectedUser, and emits user.created', () => {
    const newDetail: UserResponse = {
      ...seedDetail,
      id: 'usr-019e30c3-2c00-7100-8000-0000000000aa',
      username: 'm.gandhi',
      friendlyName: 'Marie Gandhi',
      notificationEmail: 'marie@example.test',
      invitePending: true,
    };
    const bus = configure(
      usersServiceStub({ list: () => of([seedItem]), invite: () => of(newDetail) }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(UsersStore);
    store.invite({
      username: 'm.gandhi',
      friendlyName: 'Marie Gandhi',
      notificationEmail: 'marie@example.test',
      languageId: LANG_DE,
      roles: ['PILOT'],
    });

    expect(store.selectedUser()?.id).toBe(newDetail.id);
    expect(events.some((e) => e.kind === 'user.created')).toBe(true);
  });

  it('update emits user.updated and patches the list entity', () => {
    const updatedDetail: UserResponse = { ...seedDetail, friendlyName: 'Hans Meier-Neu' };
    const bus = configure(
      usersServiceStub({ list: () => of([seedItem]), update: () => of(updatedDetail) }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(UsersStore);
    store.update({
      id: SEED_USER_ID,
      req: {
        friendlyName: 'Hans Meier-Neu',
        notificationEmail: 'hans.meier@example.test',
        languageId: LANG_DE,
        roles: ['PILOT'],
      },
    });
    expect(events.some((e) => e.kind === 'user.updated')).toBe(true);
  });

  it('deactivate removes the entity and emits user.deleted on success', () => {
    const bus = configure(
      usersServiceStub({
        list: () => of([seedItem]),
        remove: () => of(undefined as unknown as void),
      }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(UsersStore);
    store.deactivate(SEED_USER_ID);
    expect(store.entities().find((u) => u.id === SEED_USER_ID)).toBeUndefined();
    expect(events.some((e) => e.kind === 'user.deleted')).toBe(true);
  });

  it('classifies 409 self-delete by problem-detail URN', () => {
    const err = new HttpErrorResponse({
      status: 409,
      error: {
        type: 'https://alpenflight.ch/problems/user-conflict',
        detail: 'A user cannot deactivate themselves.',
      },
    });
    configure(
      usersServiceStub({ list: () => of([seedItem]), remove: () => throwError(() => err) }),
    );
    const store = TestBed.inject(UsersStore);
    store.deactivate(SELF_USER_ID);
    expect(store.saveErrorKind()).toBe('conflict-self-delete');
    expect(store.saveError()).toContain('cannot deactivate themselves');
  });

  it('classifies 409 last-admin by problem-detail URN', () => {
    const err = new HttpErrorResponse({
      status: 409,
      error: {
        type: 'https://alpenflight.ch/problems/user-conflict',
        detail: 'Cannot deactivate the last CLUB_ADMINISTRATOR of a club.',
      },
    });
    configure(
      usersServiceStub({ list: () => of([seedItem]), remove: () => throwError(() => err) }),
    );
    const store = TestBed.inject(UsersStore);
    store.deactivate(SEED_USER_ID);
    expect(store.saveErrorKind()).toBe('conflict-last-admin');
  });

  it('classifies 409 username-taken on invite', () => {
    const err = new HttpErrorResponse({
      status: 409,
      error: {
        type: 'https://alpenflight.ch/problems/user-conflict',
        detail: 'username "h.meier" already in use',
      },
    });
    configure(
      usersServiceStub({ list: () => of([seedItem]), invite: () => throwError(() => err) }),
    );
    const store = TestBed.inject(UsersStore);
    store.invite({
      username: 'h.meier',
      friendlyName: 'X',
      notificationEmail: 'x@example.test',
      languageId: LANG_DE,
      roles: ['PILOT'],
    });
    expect(store.saveErrorKind()).toBe('conflict-username-taken');
  });

  it('classifies 403 role-grant rejection', () => {
    const err = new HttpErrorResponse({
      status: 403,
      error: {
        type: 'https://alpenflight.ch/problems/user-role-grant-rejected',
        detail: 'Caller may not grant SYSTEM_ADMINISTRATOR',
      },
    });
    configure(
      usersServiceStub({ list: () => of([seedItem]), update: () => throwError(() => err) }),
    );
    const store = TestBed.inject(UsersStore);
    store.update({
      id: SEED_USER_ID,
      req: {
        friendlyName: 'X',
        notificationEmail: 'x@example.test',
        languageId: LANG_DE,
        roles: ['SYSTEM_ADMINISTRATOR'],
      },
    });
    expect(store.saveErrorKind()).toBe('role-grant-rejected');
  });

  it('resendInvite returns 204 and clears save error', () => {
    configure(
      usersServiceStub({
        list: () => of([seedItem]),
        resend: () => of(undefined as unknown as void),
      }),
    );
    const store = TestBed.inject(UsersStore);
    store.resendInvite(SEED_USER_ID);
    expect(store.saveError()).toBeNull();
  });

  it('session.logout clears state', () => {
    const bus = configure(usersServiceStub({ list: () => of([seedItem]) }));
    const store = TestBed.inject(UsersStore);
    expect(store.entities().length).toBe(1);
    bus.next({ kind: 'session.logout' });
    expect(store.entities().length).toBe(0);
  });
});

describe('mergeManagedRoles (role-diff formula)', () => {
  // (currentFromServer \ uiManagedRoles) ∪ checkedBoxes
  // Preserves SYSTEM_ADMINISTRATOR (and any future out-of-band role) on
  // profile edit so a CLUB_ADMIN never silently demotes a sysadmin user.

  it('returns checked boxes when no out-of-band roles exist', () => {
    expect(mergeManagedRoles(['PILOT'], ['CLUB_ADMINISTRATOR', 'PILOT']).sort()).toEqual(
      ['CLUB_ADMINISTRATOR', 'PILOT'].sort(),
    );
  });

  it('preserves SYSTEM_ADMINISTRATOR (outside managed catalogue) on edit', () => {
    const out = mergeManagedRoles(
      ['SYSTEM_ADMINISTRATOR', 'PILOT'],
      ['CLUB_ADMINISTRATOR', 'PILOT'],
    );
    expect(out).toContain('SYSTEM_ADMINISTRATOR');
    expect(out).toContain('CLUB_ADMINISTRATOR');
    expect(out).toContain('PILOT');
  });

  it('drops a managed role when the box is unchecked', () => {
    const out = mergeManagedRoles(['PILOT', 'GUEST'], ['PILOT']);
    expect(out).toContain('PILOT');
    expect(out).not.toContain('GUEST');
  });

  it('returns a deduplicated set even when current and checked overlap', () => {
    const out = mergeManagedRoles(['PILOT', 'GUEST'], ['PILOT', 'GUEST']);
    expect(out.length).toBe(new Set(out).size);
  });
});
