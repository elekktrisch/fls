import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { MeService } from '@api/generated/me/me.service';
import type { MePersonResponse, MePersonUpdateRequest } from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';

import { PersonalStore } from './personal.store';

const PERSON_BASE: MePersonResponse = {
  firstName: 'Pia',
  lastName: 'Lot',
  midName: 'M',
  addressLine1: 'Flugplatzstrasse 1',
  zip: '3000',
  city: 'Bern',
  region: 'BE',
  privatePhone: '+41 31 000',
  businessPhone: '+41 31 999',
  emailPrivate: 'pia@club.example',
  preferMailToBusinessMail: true,
  birthday: '1990-05-01',
};

interface MeStubControls {
  getPerson: () => Observable<MePersonResponse>;
  update: (req: MePersonUpdateRequest) => Observable<MePersonResponse>;
}

function meStub(controls: MeStubControls): MeService {
  return {
    getMyPerson: ((options?: unknown) => {
      void options;
      return controls.getPerson();
    }) as unknown as MeService['getMyPerson'],
    updateMyPerson: ((req: MePersonUpdateRequest, options?: unknown) => {
      void options;
      return controls.update(req);
    }) as unknown as MeService['updateMyPerson'],
  } as unknown as MeService;
}

function configure(controls: MeStubControls): {
  store: InstanceType<typeof PersonalStore>;
  busEvents: MutationEvent[];
} {
  const bus = new Subject<MutationEvent>();
  const busEvents: MutationEvent[] = [];
  bus.subscribe((e) => busEvents.push(e));

  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      PersonalStore,
      { provide: MeService, useValue: meStub(controls) },
      { provide: MUTATION_BUS, useValue: bus },
    ],
  });
  const store = TestBed.inject(PersonalStore);
  return { store, busEvents };
}

describe('PersonalStore', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('hydrates contact + read-only name fields from GET /me/person when load() is called', () => {
    const { store } = configure({
      getPerson: () => of(PERSON_BASE),
      update: () => of(PERSON_BASE),
    });
    store.load();
    const view = store.view();
    expect(view).not.toBeNull();
    expect(view?.firstName).toBe('Pia');
    expect(view?.lastName).toBe('Lot');
    expect(view?.addressLine1).toBe('Flugplatzstrasse 1');
    expect(view?.city).toBe('Bern');
    expect(view?.privatePhone).toBe('+41 31 000');
    expect(view?.preferMailToBusinessMail).toBe(true);
    expect(view?.birthday).toBe('1990-05-01');
    expect(store.canSave()).toBe(true);
  });

  it('surfaces an error on a failed GET /me/person load', () => {
    const err = new HttpErrorResponse({ status: 500 });
    const { store } = configure({
      getPerson: () => throwError(() => err),
      update: () => of(PERSON_BASE),
    });
    store.load();
    expect(store.hasError()).toBe(true);
    expect(store.view()).toBeNull();
  });

  it('save() persists the contact edit, re-reads the contact, and nudges the session via the bus', () => {
    const sent: MePersonUpdateRequest[] = [];
    const persisted: MePersonResponse = { ...PERSON_BASE, city: 'Zurich' };
    const { store, busEvents } = configure({
      getPerson: () => of(persisted),
      update: (req) => {
        sent.push(req);
        return of(PERSON_BASE);
      },
    });
    store.load();

    store.save({
      addressLine1: 'Flugplatzstrasse 1',
      city: 'Zurich',
      privatePhone: '+41 31 000',
      businessPhone: '+41 31 999',
      birthday: '1990-05-01',
    });

    expect(sent).toHaveLength(1);
    const req = sent[0]!;
    expect(req.city).toBe('Zurich');
    expect(store.view()?.city).toBe('Zurich');
    expect(store.savedOnce()).toBe(true);
    expect(store.isSaving()).toBe(false);
    expect(busEvents).toContainEqual({ kind: 'profile.updated' });
  });

  it('surfaces an error on a failed save without emitting the bus event', () => {
    const err = new HttpErrorResponse({ status: 400 });
    const { store, busEvents } = configure({
      getPerson: () => of(PERSON_BASE),
      update: () => throwError(() => err),
    });
    store.load();
    store.save({ city: 'X' });
    expect(store.hasError()).toBe(true);
    expect(store.isSaving()).toBe(false);
    expect(busEvents).not.toContainEqual({ kind: 'profile.updated' });
  });
});
