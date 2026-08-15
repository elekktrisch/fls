import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { MeService } from '@api/generated/me/me.service';
import type { SystemDashboardResponse } from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';

import { SystemDashboardStore } from './system-dashboard.store';

function meStub(totals: () => Observable<SystemDashboardResponse>): MeService {
  return {
    get2: ((options?: unknown) => {
      void options;
      return totals();
    }) as MeService['get2'],
  } as unknown as MeService;
}

function configure(api: MeService): { bus: Subject<MutationEvent> } {
  const bus = new Subject<MutationEvent>();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: bus },
      { provide: MeService, useValue: api },
    ],
  });
  return { bus };
}

const totals = (
  totalClubs: number,
  totalUsers: number,
  totalFlights: number,
): SystemDashboardResponse => ({ totalClubs, totalUsers, totalFlights });

describe('SystemDashboardStore', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('initialises and loads the totals on construct', () => {
    configure(meStub(() => of(totals(2, 14, 87))));
    const store = TestBed.inject(SystemDashboardStore);
    expect(store.totalClubs()).toBe(2);
    expect(store.totalUsers()).toBe(14);
    expect(store.totalFlights()).toBe(87);
    expect(store.isLoading()).toBe(false);
    expect(store.hasError()).toBe(false);
    expect(store.showTotals()).toBe(true);
  });

  it('surfaces an error on HTTP failure and reports no totals', () => {
    const err = new HttpErrorResponse({ status: 500, statusText: 'Server Error' });
    configure(meStub(() => throwError(() => err)));
    const store = TestBed.inject(SystemDashboardStore);
    expect(store.hasError()).toBe(true);
    expect(store.isLoading()).toBe(false);
    expect(store.showTotals()).toBe(false);
  });

  it('clears its state on session.logout / session.tenantSwitch', () => {
    const { bus } = configure(meStub(() => of(totals(3, 9, 40))));
    const store = TestBed.inject(SystemDashboardStore);
    expect(store.totalClubs()).toBe(3);
    bus.next({ kind: 'session.tenantSwitch', clubId: 'club-2' });
    expect(store.totalClubs()).toBe(0);
    expect(store.hasLoaded()).toBe(false);
  });
});
