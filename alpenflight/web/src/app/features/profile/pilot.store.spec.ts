import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { MeService } from '@api/generated/me/me.service';
import type { MePersonLicencesResponse, MePersonLicencesUpdateRequest } from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';

import { PilotStore } from './pilot.store';

const LICENCES_BASE: MePersonLicencesResponse = {
  hasGliderPilotLicence: true,
  hasTowPilotLicence: false,
  licenceNumber: 'CH-GLD-0001',
  medicalClass2ExpireDate: '2027-09-30',
  hasGliderTowingStartPermission: true,
  receiveOwnedAircraftStatisticReports: false,
};

interface MeStubControls {
  get: () => Observable<MePersonLicencesResponse>;
  update: (req: MePersonLicencesUpdateRequest) => Observable<MePersonLicencesResponse>;
}

function meStub(controls: MeStubControls): MeService {
  return {
    getMyLicences: ((options?: unknown) => {
      void options;
      return controls.get();
    }) as unknown as MeService['getMyLicences'],
    updateMyLicences: ((req: MePersonLicencesUpdateRequest, options?: unknown) => {
      void options;
      return controls.update(req);
    }) as unknown as MeService['updateMyLicences'],
  } as unknown as MeService;
}

function configure(controls: MeStubControls): {
  store: InstanceType<typeof PilotStore>;
  busEvents: MutationEvent[];
} {
  const bus = new Subject<MutationEvent>();
  const busEvents: MutationEvent[] = [];
  bus.subscribe((e) => busEvents.push(e));

  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      PilotStore,
      { provide: MeService, useValue: meStub(controls) },
      { provide: MUTATION_BUS, useValue: bus },
    ],
  });
  const store = TestBed.inject(PilotStore);
  return { store, busEvents };
}

describe('PilotStore', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('hydrates the licence/medical view from getMyLicences, defaulting absent fields', () => {
    const { store } = configure({
      get: () => of(LICENCES_BASE),
      update: () => of(LICENCES_BASE),
    });
    store.load();
    const view = store.view();
    expect(view).not.toBeNull();
    expect(view?.hasGliderPilotLicence).toBe(true);
    expect(view?.licenceNumber).toBe('CH-GLD-0001');
    expect(view?.medicalClass2ExpireDate).toBe('2027-09-30');
    expect(view?.hasMotorPilotLicence).toBe(false);
    expect(view?.medicalClass1ExpireDate).toBe('');
    expect(store.canSave()).toBe(true);
  });

  it('surfaces an error on a failed load', () => {
    const err = new HttpErrorResponse({ status: 409 });
    const { store } = configure({
      get: () => throwError(() => err),
      update: () => of(LICENCES_BASE),
    });
    store.load();
    expect(store.hasError()).toBe(true);
    expect(store.view()).toBeNull();
  });

  it('save() persists the licence edit, reflects the projection, and nudges the session via the bus', () => {
    const sent: MePersonLicencesUpdateRequest[] = [];
    const { store, busEvents } = configure({
      get: () => of(LICENCES_BASE),
      update: (req) => {
        sent.push(req);
        return of({ ...LICENCES_BASE, hasTowPilotLicence: true });
      },
    });
    store.load();

    store.save({ hasTowPilotLicence: true, licenceNumber: 'CH-GLD-0002' });

    expect(sent).toHaveLength(1);
    const req = sent[0]!;
    expect(req.hasTowPilotLicence).toBe(true);
    expect(req.licenceNumber).toBe('CH-GLD-0002');
    expect(store.view()?.hasTowPilotLicence).toBe(true);
    expect(store.savedOnce()).toBe(true);
    expect(store.isSaving()).toBe(false);
    expect(busEvents).toContainEqual({ kind: 'profile.updated' });
  });

  it('surfaces an error on a failed save without emitting the bus event', () => {
    const err = new HttpErrorResponse({ status: 400 });
    const { store, busEvents } = configure({
      get: () => of(LICENCES_BASE),
      update: () => throwError(() => err),
    });
    store.load();
    store.save({ hasGliderPilotLicence: false });
    expect(store.hasError()).toBe(true);
    expect(store.isSaving()).toBe(false);
    expect(busEvents).not.toContainEqual({ kind: 'profile.updated' });
  });
});
