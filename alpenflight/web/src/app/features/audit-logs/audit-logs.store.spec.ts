import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { AuditEventsService } from '@api/generated/audit-events/audit-events.service';
import { UsersService } from '@api/generated/users/users.service';
import type { AuditEventPage, UserListItem } from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';
import { AuditLogsStore } from './audit-logs.store';

const ACTOR_USER_ID = '019e30c3-2c00-7100-8000-000000000002';

const actor: UserListItem = {
  id: `usr-${ACTOR_USER_ID}`,
  username: 'h.meier',
  friendlyName: 'Hans Meier',
  notificationEmail: 'hans.meier@example.test',
  roles: ['PILOT'],
  enabled: true,
  invitePending: false,
};

const emptyPage: AuditEventPage = { items: [], hasMore: false };

function configure(listUsers: () => Observable<UserListItem[]>): void {
  const auditApi = {
    listAuditEvents: ((params?: unknown, options?: unknown) => {
      void params;
      void options;
      return of(emptyPage);
    }) as AuditEventsService['listAuditEvents'],
  } as unknown as AuditEventsService;
  const usersApi = {
    listUsers: ((options?: unknown) => {
      void options;
      return listUsers();
    }) as UsersService['listUsers'],
  } as unknown as UsersService;
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: new Subject<MutationEvent>() },
      { provide: AuditEventsService, useValue: auditApi },
      { provide: UsersService, useValue: usersApi },
    ],
  });
}

describe('AuditLogsStore actor-name lookup', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('reports a 403 instead of silently leaving every actor cell on its Keycloak sub', () => {
    configure(() =>
      throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    );
    const store = TestBed.inject(AuditLogsStore);

    store.loadActorUsernames();

    expect(store.actorUsernamesByUserId()).toEqual({});
    expect(store.actorNamesAreUnresolved()).toBe(true);
    expect(store.actorNameLookupError()).toBe('HTTP 403');
  });

  it('resolves the actor names and reports no failure when the lookup answers', () => {
    configure(() => of([actor]));
    const store = TestBed.inject(AuditLogsStore);

    store.loadActorUsernames();

    expect(store.actorUsernamesByUserId()).toEqual({ [ACTOR_USER_ID]: 'h.meier' });
    expect(store.actorNamesAreUnresolved()).toBe(false);
    expect(store.actorNameLookupError()).toBeNull();
  });
});
