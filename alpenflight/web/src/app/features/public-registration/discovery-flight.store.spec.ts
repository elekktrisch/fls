import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import type {
  DiscoveryFlightRegistrationRequest,
  DiscoveryFlightRegistrationResponse,
  PublicRegistrantDetails,
} from '@api/generated/model';
import { PublicRegistrationService } from '@api/generated/public-registration/public-registration.service';

import { DiscoveryFlightStore } from './discovery-flight.store';

// @mocked: http — store unit test

const CLUB_SLUG = 'alpine-soaring';
const DAYS = ['2099-06-15', '2099-08-25'];

const registrant: PublicRegistrantDetails = {
  firstname: 'Nina',
  lastname: 'Brunner',
  addressLine1: 'Flugplatzstrasse 12',
  zip: '8600',
  city: 'Dübendorf',
  privateEmail: 'nina.brunner@example.com',
  invoiceAddressIsSame: true,
};

const accepted: DiscoveryFlightRegistrationResponse = {
  registrantPersonId: 'pn-019e30c3-2c00-7001-8000-000000000777',
  clubName: 'Alpine Soaring',
  selectedDay: DAYS[0]!,
};

interface ApiStubs {
  listPublicDiscoveryFlightDays: (clubSlug: string) => Observable<string[]>;
  submitDiscoveryFlightRegistration: (
    clubSlug: string,
    body: DiscoveryFlightRegistrationRequest,
  ) => Observable<DiscoveryFlightRegistrationResponse>;
}

function serviceStub(stubs: Partial<ApiStubs>): PublicRegistrationService {
  const api = {
    listPublicDiscoveryFlightDays: ((clubSlug: string, options?: unknown) => {
      void options;
      return (stubs.listPublicDiscoveryFlightDays ?? (() => of(DAYS)))(clubSlug);
    }) as PublicRegistrationService['listPublicDiscoveryFlightDays'],
    submitDiscoveryFlightRegistration: ((
      clubSlug: string,
      body: DiscoveryFlightRegistrationRequest,
      options?: unknown,
    ) => {
      void options;
      return (stubs.submitDiscoveryFlightRegistration ?? (() => of(accepted)))(clubSlug, body);
    }) as PublicRegistrationService['submitDiscoveryFlightRegistration'],
  };
  return api as unknown as PublicRegistrationService;
}

function configure(api: PublicRegistrationService): InstanceType<typeof DiscoveryFlightStore> {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: PublicRegistrationService, useValue: api },
      DiscoveryFlightStore,
    ],
  });
  return TestBed.inject(DiscoveryFlightStore);
}

function httpError(status: number, headers?: Record<string, string>): HttpErrorResponse {
  return new HttpErrorResponse({
    status,
    statusText: '',
    ...(headers ? { headers: new HttpHeaders(headers) } : {}),
  });
}

describe('DiscoveryFlightStore — club lookup', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('holds the published days ascending and opens the form', () => {
    const store = configure(serviceStub({}));

    store.loadClub(CLUB_SLUG);

    expect(store.days()).toEqual(DAYS);
    expect(store.resolution()).toBe('ready');
    expect(store.formState()).toBe('ready');
  });

  it('treats an empty day list as a bookable club with nothing published', () => {
    const store = configure(serviceStub({ listPublicDiscoveryFlightDays: () => of([]) }));

    store.loadClub(CLUB_SLUG);

    expect(store.days()).toEqual([]);
    expect(store.formState()).toBe('ready');
  });

  it('maps 404 to not-found and 403 to unavailable', () => {
    const notFound = configure(
      serviceStub({ listPublicDiscoveryFlightDays: () => throwError(() => httpError(404)) }),
    );
    notFound.loadClub('no-such-club');
    expect(notFound.formState()).toBe('not-found');

    TestBed.resetTestingModule();

    const closed = configure(
      serviceStub({ listPublicDiscoveryFlightDays: () => throwError(() => httpError(403)) }),
    );
    closed.loadClub('registration-closed-club');
    expect(closed.formState()).toBe('unavailable');
    expect(closed.days()).toEqual([]);
  });

  it('names the club by its slug until an accepted registration returns the real name', () => {
    const store = configure(serviceStub({}));

    store.loadClub(CLUB_SLUG);
    expect(store.clubHeading()).toBe(CLUB_SLUG);

    store.submit(registrant, DAYS[0]!);
    expect(store.clubHeading()).toBe('Alpine Soaring');
  });
});

describe('DiscoveryFlightStore — submit', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('nests the registrant beside the selected day, as the endpoint expects', () => {
    let sentSlug: string | null = null;
    let sentBody: DiscoveryFlightRegistrationRequest | null = null;
    const store = configure(
      serviceStub({
        submitDiscoveryFlightRegistration: (clubSlug, body) => {
          sentSlug = clubSlug;
          sentBody = body;
          return of(accepted);
        },
      }),
    );

    store.loadClub(CLUB_SLUG);
    store.submit(registrant, DAYS[0]!);

    expect(sentSlug).toBe(CLUB_SLUG);
    expect(sentBody).toEqual({ registrant, selectedDay: DAYS[0] });
    expect(store.registration()).toEqual(accepted);
    expect(store.formState()).toBe('success');
    expect(store.submitting()).toBe(false);
    expect(store.failure()).toBeNull();
  });

  it('maps 429 to the throttled notice with its Retry-After budget', () => {
    const store = configure(
      serviceStub({
        submitDiscoveryFlightRegistration: () =>
          throwError(() => httpError(429, { 'Retry-After': '60' })),
      }),
    );

    store.loadClub(CLUB_SLUG);
    store.submit(registrant, DAYS[0]!);

    expect(store.failure()).toBe('throttled');
    expect(store.retryAfterSeconds()).toBe(60);
    expect(store.registration()).toBeNull();
    expect(store.formState()).toBe('ready');
  });

  it('maps every other rejection to the generic failure, keeping the form open', () => {
    const store = configure(
      serviceStub({ submitDiscoveryFlightRegistration: () => throwError(() => httpError(400)) }),
    );

    store.loadClub(CLUB_SLUG);
    store.submit(registrant, DAYS[0]!);

    expect(store.failure()).toBe('failed');
    expect(store.retryAfterSeconds()).toBe(0);
    expect(store.formState()).toBe('ready');
  });
});
