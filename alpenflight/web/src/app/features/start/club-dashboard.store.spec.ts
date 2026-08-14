import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { MeService } from '@api/generated/me/me.service';
import type { ClubDashboardResponse } from '@api/generated/model';

import { MeEventsService } from '../../core/events';
import type { MeEvent } from '../../core/events';
import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';

import { ClubDashboardStore } from './club-dashboard.store';


function meStub(counts: () => Observable<ClubDashboardResponse>): MeService {
  return {
    get3: ((options?: unknown) => {
      void options;
      return counts();
    }) as MeService['get3'],
  } as unknown as MeService;
}

function eventsStub(flightCreated: Subject<MeEvent>): MeEventsService {
  return {
    on: (kind: string): Observable<MeEvent> => {
      if (kind === 'flight.created') return flightCreated.asObservable();
      return new Subject<MeEvent>().asObservable();
    },
  } as unknown as MeEventsService;
}

interface Harness {
  bus: Subject<MutationEvent>;
  flightCreated: Subject<MeEvent>;
}

function configure(api: MeService, flightCreated = new Subject<MeEvent>()): Harness {
  const bus = new Subject<MutationEvent>();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: bus },
      { provide: MeService, useValue: api },
      { provide: MeEventsService, useValue: eventsStub(flightCreated) },
    ],
  });
  return { bus, flightCreated };
}

const counts = (todaysFlights: number, pendingValidation: number): ClubDashboardResponse => ({
  todaysFlights,
  pendingValidation,
});

const event = (data = ''): MeEvent => ({ kind: 'flight.created', data, id: null });

describe('ClubDashboardStore', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('initialises and loads the counts on construct', () => {
    configure(meStub(() => of(counts(3, 5))));
    const store = TestBed.inject(ClubDashboardStore);
    expect(store.todaysFlights()).toBe(3);
    expect(store.pendingValidation()).toBe(5);
    expect(store.isLoading()).toBe(false);
    expect(store.hasError()).toBe(false);
    expect(store.showCounts()).toBe(true);
  });

  it('surfaces an error on HTTP failure and reports no counts', () => {
    const err = new HttpErrorResponse({ status: 500, statusText: 'Server Error' });
    configure(meStub(() => throwError(() => err)));
    const store = TestBed.inject(ClubDashboardStore);
    expect(store.hasError()).toBe(true);
    expect(store.isLoading()).toBe(false);
    expect(store.showCounts()).toBe(false);
  });

  it('re-fetches the counts on a flight.created SSE event and updates today-count', () => {
    let current = counts(2, 1);
    const { flightCreated } = configure(meStub(() => of(current)));
    const store = TestBed.inject(ClubDashboardStore);
    expect(store.todaysFlights()).toBe(2);

    current = counts(3, 1);
    flightCreated.next(event());
    expect(store.todaysFlights()).toBe(3);
    expect(store.pendingValidation()).toBe(1);
  });

  it('keeps the previous counts visible while a re-fetch errors', () => {
    let result: Observable<ClubDashboardResponse> = of(counts(4, 2));
    const { flightCreated } = configure(meStub(() => result));
    const store = TestBed.inject(ClubDashboardStore);
    expect(store.todaysFlights()).toBe(4);

    result = throwError(() => new HttpErrorResponse({ status: 500 }));
    flightCreated.next(event());
    expect(store.hasError()).toBe(true);
  });

  it('clears its state on session.logout / session.tenantSwitch', () => {
    const { bus } = configure(meStub(() => of(counts(7, 9))));
    const store = TestBed.inject(ClubDashboardStore);
    expect(store.todaysFlights()).toBe(7);
    bus.next({ kind: 'session.logout' });
    expect(store.todaysFlights()).toBe(0);
    expect(store.hasLoaded()).toBe(false);
  });
});
