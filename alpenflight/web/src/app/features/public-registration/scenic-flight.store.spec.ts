import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type {
  PublicRegistrantDetails,
  ScenicFlightRegistrationRequest,
  ScenicFlightRegistrationResponse,
} from '@api/generated/model';
import { PublicRegistrationService } from '@api/generated/public-registration/public-registration.service';

import { ScenicFlightStore } from './scenic-flight.store';

// @mocked: http — store unit test

const CLUB_SLUG = 'alpine-soaring';

const registrant: PublicRegistrantDetails = {
  firstname: 'Livia',
  lastname: 'Keller',
  addressLine1: 'Flugplatzstrasse 12',
  zip: '8600',
  city: 'Dübendorf',
  privateEmail: 'livia.keller@example.com',
  invoiceAddressIsSame: true,
};

const accepted: ScenicFlightRegistrationResponse = {
  registrantPersonId: 'pn-019e30c3-2c00-7001-8000-000000000778',
  clubName: 'Alpine Soaring',
};

interface ApiStubs {
  submitScenicFlightRegistration: (
    clubSlug: string,
    body: ScenicFlightRegistrationRequest,
  ) => Observable<ScenicFlightRegistrationResponse>;
}

function serviceStub(stubs: Partial<ApiStubs>, listDays = vi.fn()): PublicRegistrationService {
  const api = {
    listPublicDiscoveryFlightDays:
      listDays as PublicRegistrationService['listPublicDiscoveryFlightDays'],
    submitScenicFlightRegistration: ((
      clubSlug: string,
      body: ScenicFlightRegistrationRequest,
      options?: unknown,
    ) => {
      void options;
      return (stubs.submitScenicFlightRegistration ?? (() => of(accepted)))(clubSlug, body);
    }) as PublicRegistrationService['submitScenicFlightRegistration'],
  };
  return api as unknown as PublicRegistrationService;
}

function configure(api: PublicRegistrationService): InstanceType<typeof ScenicFlightStore> {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: PublicRegistrationService, useValue: api },
      ScenicFlightStore,
    ],
  });
  return TestBed.inject(ScenicFlightStore);
}

function httpError(status: number, headers?: Record<string, string>): HttpErrorResponse {
  return new HttpErrorResponse({
    status,
    statusText: '',
    ...(headers ? { headers: new HttpHeaders(headers) } : {}),
  });
}

describe('ScenicFlightStore — opening the form', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('opens the form without reading anything: scenic has no day to publish', () => {
    const listDays = vi.fn();
    const store = configure(serviceStub({}, listDays));

    store.openClub(CLUB_SLUG);

    expect(store.formState()).toBe('ready');
    expect(listDays).not.toHaveBeenCalled();
  });

  it('names the club by its slug until an accepted registration returns the real name', () => {
    const store = configure(serviceStub({}));

    store.openClub(CLUB_SLUG);
    expect(store.clubHeading()).toBe(CLUB_SLUG);

    store.submit(registrant);
    expect(store.clubHeading()).toBe('Alpine Soaring');
  });
});

describe('ScenicFlightStore — submit', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('posts the registrant alone — a scenic body carrying a day is refused with 400', () => {
    let sentSlug: string | null = null;
    let sentBody: ScenicFlightRegistrationRequest | null = null;
    const store = configure(
      serviceStub({
        submitScenicFlightRegistration: (clubSlug, body) => {
          sentSlug = clubSlug;
          sentBody = body;
          return of(accepted);
        },
      }),
    );

    store.openClub(CLUB_SLUG);
    store.submit(registrant);

    expect(sentSlug).toBe(CLUB_SLUG);
    // Exact, not a subset: the endpoint rejects an unknown property, deliberately,
    // so a dropped day cannot read back as a booked slot.
    expect(sentBody).toEqual({ registrant });
    expect(Object.keys(sentBody!)).toEqual(['registrant']);
    expect(sentBody).not.toHaveProperty('selectedDay');
    expect(store.formState()).toBe('success');
    expect(store.submitting()).toBe(false);
    expect(store.failure()).toBeNull();
  });

  it('closes the form on the statuses that adjudicate the slug itself', () => {
    const unknown = configure(
      serviceStub({ submitScenicFlightRegistration: () => throwError(() => httpError(404)) }),
    );
    unknown.openClub('no-such-club');
    unknown.submit(registrant);
    expect(unknown.formState()).toBe('not-found');
    // Not a retryable failure: re-submitting would be refused the same way.
    expect(unknown.failure()).toBeNull();

    TestBed.resetTestingModule();

    const closed = configure(
      serviceStub({ submitScenicFlightRegistration: () => throwError(() => httpError(403)) }),
    );
    closed.openClub('registration-closed-club');
    closed.submit(registrant);
    expect(closed.formState()).toBe('unavailable');
    expect(closed.failure()).toBeNull();
  });

  it('maps 429 to the throttled notice with its Retry-After budget', () => {
    const store = configure(
      serviceStub({
        submitScenicFlightRegistration: () =>
          throwError(() => httpError(429, { 'Retry-After': '60' })),
      }),
    );

    store.openClub(CLUB_SLUG);
    store.submit(registrant);

    expect(store.failure()).toBe('throttled');
    expect(store.retryAfterSeconds()).toBe(60);
    expect(store.registration()).toBeNull();
    expect(store.formState()).toBe('ready');
  });

  it('maps every other rejection to the generic failure, keeping the form open', () => {
    const store = configure(
      serviceStub({ submitScenicFlightRegistration: () => throwError(() => httpError(500)) }),
    );

    store.openClub(CLUB_SLUG);
    store.submit(registrant);

    expect(store.failure()).toBe('failed');
    expect(store.retryAfterSeconds()).toBe(0);
    expect(store.formState()).toBe('ready');
  });
});
