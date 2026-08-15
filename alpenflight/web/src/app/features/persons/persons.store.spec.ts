import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { PersonsService } from '@api/generated/persons/persons.service';
import type {
  PersonClubRequest,
  PersonClubResponse,
  PersonListItem,
  PersonResponse,
  PersonUpdateRequest,
} from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';
import { PersonsStore } from './persons.store';

const PERSON_ID = 'pn-019e30c3-2c00-7001-8000-000000000a01';

const seedMembership: PersonClubResponse = {
  id: '019e2e15-2c00-7d01-8000-000000000d01',
  clubId: 'clb-019e30c3-2c00-7001-8000-000000000001',
  memberNumber: 'M-001',
  memberStateId: '019e2e15-2c00-7c01-8000-000000000c01',
  memberStateName: 'Active',
  isMotorPilot: false,
  isTowPilot: false,
  isGliderInstructor: false,
  isGliderPilot: true,
  isGliderTrainee: false,
  isPassenger: false,
  isWinchOperator: true,
  isMotorInstructor: false,
  receiveFlightReports: true,
  receiveAircraftReservationNotifications: false,
  receivePlanningDayRoleReminder: false,
  isActive: true,
};

const seedDetail: PersonResponse = {
  id: PERSON_ID,
  firstname: 'Anna',
  lastname: 'Bühler',
  preferMailToBusinessMail: false,
  hasMotorPilotLicence: false,
  hasTowPilotLicence: false,
  hasGliderInstructorLicence: false,
  hasGliderPilotLicence: true,
  hasGliderTraineeLicence: false,
  hasGliderPaxLicence: false,
  hasTmgLicence: false,
  hasWinchOperatorLicence: false,
  hasMotorInstructorLicence: false,
  hasPartMLicence: false,
  hasGliderTowingStartPermission: false,
  hasGliderSelfStartPermission: false,
  hasGliderWinchStartPermission: false,
  receiveOwnedAircraftStatisticReports: false,
  enableAddress: false,
  memberships: [seedMembership],
  inOtherClubsCount: 0,
};

const seedListItem: PersonListItem = {
  id: PERSON_ID,
  firstname: 'Anna',
  lastname: 'Bühler',
  memberNumber: 'M-001',
  isActive: true,
  isMotorPilot: false,
  isTowPilot: false,
  isGliderInstructor: false,
  isGliderPilot: true,
  isGliderTrainee: false,
  isWinchOperator: true,
  isMotorInstructor: false,
};

const updateReq: PersonUpdateRequest = {
  firstname: 'Anna',
  lastname: 'Bühler',
  preferMailToBusinessMail: false,
  receiveOwnedAircraftStatisticReports: false,
  enableAddress: false,
};

const membershipReq: PersonClubRequest = {
  memberNumber: 'M-777',
  memberStateId: '019e2e15-2c00-7c02-8000-000000000c02',
  isMotorPilot: true,
  isTowPilot: false,
  isGliderInstructor: false,
  isGliderPilot: true,
  isGliderTrainee: false,
  isPassenger: false,
  isWinchOperator: true,
  isMotorInstructor: false,
  receiveFlightReports: true,
  receiveAircraftReservationNotifications: false,
  receivePlanningDayRoleReminder: false,
  isActive: true,
};

interface ApiStubs {
  list: () => Observable<PersonListItem[]>;
  update: (id: string, req: PersonUpdateRequest) => Observable<PersonResponse>;
  updateMembership: (id: string, req: PersonClubRequest) => Observable<PersonResponse>;
}

function personsServiceStub(stubs: Partial<ApiStubs>): PersonsService {
  const api = {
    listPersons: ((options?: unknown) => {
      void options;
      return (stubs.list ?? (() => of([seedListItem])))();
    }) as PersonsService['listPersons'],
    updatePerson: ((id: string, req: PersonUpdateRequest, options?: unknown) => {
      void options;
      return (stubs.update ?? (() => of(seedDetail)))(id, req);
    }) as PersonsService['updatePerson'],
    updateCurrentClubMembership: ((id: string, req: PersonClubRequest, options?: unknown) => {
      void options;
      return (stubs.updateMembership ?? (() => of(seedDetail)))(id, req);
    }) as PersonsService['updateCurrentClubMembership'],
  };
  return api as unknown as PersonsService;
}

function configure(api: PersonsService): Subject<MutationEvent> {
  const bus = new Subject<MutationEvent>();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: bus },
      { provide: PersonsService, useValue: api },
    ],
  });
  return bus;
}

describe('PersonsStore — forked update (person + current-club membership)', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('PUTs the person THEN clubs/current, patches state from the membership response, and emits person.updated once', () => {
    const calls: string[] = [];
    let membershipPayload: PersonClubRequest | null = null;
    const afterBoth: PersonResponse = {
      ...seedDetail,
      memberships: [
        {
          ...seedMembership,
          memberNumber: 'M-777',
          memberStateId: membershipReq.memberStateId as string,
          memberStateName: 'Honorary',
          isMotorPilot: true,
        },
      ],
    };
    let listState: PersonListItem[] = [seedListItem];
    const bus = configure(
      personsServiceStub({
        list: () => of(listState),
        update: (id, req) => {
          calls.push(`person:${id}`);
          void req;
          return of(seedDetail);
        },
        updateMembership: (id, req) => {
          calls.push(`membership:${id}`);
          membershipPayload = req;
          listState = [{ ...seedListItem, memberNumber: 'M-777', isMotorPilot: true }];
          return of(afterBoth);
        },
      }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(PersonsStore);
    store.update({ id: PERSON_ID, req: updateReq, membership: membershipReq });

    expect(calls).toEqual([`person:${PERSON_ID}`, `membership:${PERSON_ID}`]);
    expect(membershipPayload).toEqual(membershipReq);
    expect(store.selectedDetail()?.memberships[0]?.memberNumber).toBe('M-777');
    expect(store.selectedDetail()?.memberships[0]?.isMotorPilot).toBe(true);
    expect(store.entities().find((p) => p.id === PERSON_ID)?.memberNumber).toBe('M-777');
    expect(events).toEqual([{ kind: 'person.updated', id: PERSON_ID }]);
    expect(store.saveError()).toBeNull();
  });

  it('skips the clubs/current PUT when no membership payload is given', () => {
    const calls: string[] = [];
    const bus = configure(
      personsServiceStub({
        update: (id) => {
          calls.push(`person:${id}`);
          return of(seedDetail);
        },
        updateMembership: (id) => {
          calls.push(`membership:${id}`);
          return of(seedDetail);
        },
      }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(PersonsStore);
    store.update({ id: PERSON_ID, req: updateReq });

    expect(calls).toEqual([`person:${PERSON_ID}`]);
    expect(events).toEqual([{ kind: 'person.updated', id: PERSON_ID }]);
  });

  it('surfaces the error and does NOT emit person.updated when the membership half fails', () => {
    const err = new HttpErrorResponse({ status: 409, statusText: 'Conflict' });
    const bus = configure(
      personsServiceStub({
        update: () => of(seedDetail),
        updateMembership: () => throwError(() => err),
      }),
    );
    const events: MutationEvent[] = [];
    bus.subscribe((e) => events.push(e));

    const store = TestBed.inject(PersonsStore);
    store.update({ id: PERSON_ID, req: updateReq, membership: membershipReq });

    expect(events).toEqual([]);
    expect(store.saveError()).not.toBeNull();
    expect(store.saveErrorKind()).not.toBeNull();
  });
});

describe('PersonsStore — errorPatch classification (J-26 T-23 classifyApiError fold)', () => {
  afterEach(() => TestBed.resetTestingModule());

  function dispatchUpdateError(error: HttpErrorResponse) {
    configure(personsServiceStub({ update: () => throwError(() => error) }));
    const store = TestBed.inject(PersonsStore);
    store.update({ id: PERSON_ID, req: updateReq });
    return store;
  }

  it('403 → forbidden with the hardcoded message', () => {
    const store = dispatchUpdateError(
      new HttpErrorResponse({ status: 403, statusText: 'Forbidden' }),
    );
    expect(store.saveErrorKind()).toBe('forbidden');
    expect(store.saveError()).toBe(
      'You are not authorized to perform this action on the selected person.',
    );
  });

  it('409 cross-tenant URN → cross-tenant-blocked (detail surfaced)', () => {
    const store = dispatchUpdateError(
      new HttpErrorResponse({
        status: 409,
        error: { type: 'urn:problem:cross-tenant-delete', detail: 'Active elsewhere.' },
      }),
    );
    expect(store.saveErrorKind()).toBe('cross-tenant-blocked');
    expect(store.saveError()).toBe('Active elsewhere.');
  });

  it('409 duplicate-membership URN → duplicate-membership', () => {
    const store = dispatchUpdateError(
      new HttpErrorResponse({
        status: 409,
        error: { type: 'urn:problem:duplicate-membership' },
      }),
    );
    expect(store.saveErrorKind()).toBe('duplicate-membership');
    expect(store.saveError()).toBe('Person already has an active membership in this club.');
  });

  it('409 with no recognised URN → other (broad conflict catch-all)', () => {
    const store = dispatchUpdateError(
      new HttpErrorResponse({ status: 409, error: { detail: 'Some conflict.' } }),
    );
    expect(store.saveErrorKind()).toBe('other');
    expect(store.saveError()).toBe('Some conflict.');
  });

  it('400 with field+message → field-prefixed other (the generic tail)', () => {
    const store = dispatchUpdateError(
      new HttpErrorResponse({ status: 400, error: { field: 'email', message: 'invalid' } }),
    );
    expect(store.saveErrorKind()).toBe('other');
    expect(store.saveError()).toBe('email: invalid');
  });

  it('empty body → other with the raw e.message (never an empty detail)', () => {
    const store = dispatchUpdateError(
      new HttpErrorResponse({ status: 500, statusText: 'Server Error', error: null }),
    );
    expect(store.saveErrorKind()).toBe('other');
    expect(store.saveError()).not.toBe('');
  });
});
