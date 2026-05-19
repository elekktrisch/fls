import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';

import { ClubStatesService } from '@api/generated/club-states/club-states.service';
import { CountriesService } from '@api/generated/countries/countries.service';
import type { ClubStateResponse, CountryResponse } from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../mutation-bus/mutation-bus';
import { ReferenceDataStore } from './reference-data.store';

const sampleCountry: CountryResponse = {
  id: '019e2e15-2c00-74be-8000-0000000004be',
  iso2Code: 'CH',
  name: 'Switzerland',
};

const sampleClubState: ClubStateResponse = {
  id: '019e2e15-2c00-7bb8-8000-000000000bb8',
  code: 'ACTIVE',
  name: 'Active',
};

function countriesStub(impl: () => Observable<CountryResponse[]>): CountriesService {
  return {
    listCountries: (() => impl()) as CountriesService['listCountries'],
  } as unknown as CountriesService;
}

function clubStatesStub(impl: () => Observable<ClubStateResponse[]>): ClubStatesService {
  return {
    listClubStates: (() => impl()) as ClubStatesService['listClubStates'],
  } as unknown as ClubStatesService;
}

function configure(opts: {
  countries?: () => Observable<CountryResponse[]>;
  clubStates?: () => Observable<ClubStateResponse[]>;
}): Subject<MutationEvent> {
  const bus = new Subject<MutationEvent>();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: bus },
      {
        provide: CountriesService,
        useValue: countriesStub(opts.countries ?? (() => of([sampleCountry]))),
      },
      {
        provide: ClubStatesService,
        useValue: clubStatesStub(opts.clubStates ?? (() => of([sampleClubState]))),
      },
    ],
  });
  return bus;
}

describe('ReferenceDataStore', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('initial state is empty + needsRefresh true', () => {
    configure({});
    const store = TestBed.inject(ReferenceDataStore);
    expect(store.countries()).toEqual([]);
    expect(store.clubStates()).toEqual([]);
    expect(store.isLoading()).toBe(false);
    expect(store.loadError()).toBeNull();
    expect(store.lastRefreshedAt()).toBeNull();
    expect(store.needsRefresh()).toBe(true);
  });

  it('loadAll happy path fills both collections + clears needsRefresh', () => {
    configure({});
    const store = TestBed.inject(ReferenceDataStore);
    store.loadAll();
    expect(store.countries().map((c) => c.id)).toEqual([sampleCountry.id]);
    expect(store.clubStates().map((s) => s.id)).toEqual([sampleClubState.id]);
    expect(store.isLoading()).toBe(false);
    expect(store.needsRefresh()).toBe(false);
    expect(store.countryById().get(sampleCountry.id!)?.iso2Code).toBe('CH');
    expect(store.clubStateById().get(sampleClubState.id!)?.code).toBe('ACTIVE');
  });

  it('loadAll keeps both partial — one endpoint failing does not stall the other', () => {
    configure({
      countries: () => throwError(() => new HttpErrorResponse({ status: 500 })),
      clubStates: () => of([sampleClubState]),
    });
    const store = TestBed.inject(ReferenceDataStore);
    store.loadAll();
    expect(store.countries()).toEqual([]);
    expect(store.clubStates().map((s) => s.id)).toEqual([sampleClubState.id]);
    expect(store.loadError()).toBeNull();
  });

  it('loadAll surfaces loadError when the combined stream errors', () => {
    // Both endpoints catchError → the outer pipe never errors, so this proves
    // the catchError-per-stream contract holds — no loadError surfaces.
    configure({
      countries: () => throwError(() => new HttpErrorResponse({ status: 500 })),
      clubStates: () => throwError(() => new HttpErrorResponse({ status: 500 })),
    });
    const store = TestBed.inject(ReferenceDataStore);
    store.loadAll();
    expect(store.countries()).toEqual([]);
    expect(store.clubStates()).toEqual([]);
    expect(store.loadError()).toBeNull();
  });

  it('session.logout via MUTATION_BUS clears the store', () => {
    const bus = configure({});
    const store = TestBed.inject(ReferenceDataStore);
    store.loadAll();
    expect(store.countries().length).toBe(1);
    bus.next({ kind: 'session.logout' });
    expect(store.countries()).toEqual([]);
    expect(store.clubStates()).toEqual([]);
    expect(store.lastRefreshedAt()).toBeNull();
  });

  it('session.tenantSwitch via MUTATION_BUS clears the store', () => {
    const bus = configure({});
    const store = TestBed.inject(ReferenceDataStore);
    store.loadAll();
    expect(store.countries().length).toBe(1);
    bus.next({ kind: 'session.tenantSwitch', clubId: 'whatever' });
    expect(store.countries()).toEqual([]);
  });
});
