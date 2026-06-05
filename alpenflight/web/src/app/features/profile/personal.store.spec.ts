import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { MeService } from '@api/generated/me/me.service';
import type { MePersonResponse, MePersonUpdateRequest } from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';

import { PersonalStore } from './personal.store';

/**
 * Logic test for the Personal-tab store (J-4 T-07 form, T-18 hydrate): the
 * caller-scoped `GET /api/v1/me/person` (`getMyPerson`) hydrates the editable
 * contact / address fields AND the read-only name fields, `save()` round-trips
 * through `updateMyPerson` then re-reads via `getMyPerson` so the form reflects
 * the persisted contact, and a `profile.updated` event is emitted so the session
 * re-reads `/me`. The form DOM + the real PATCH wiring are proven by the
 * real-idp e2e (`profile/self-edit.spec.ts`). Per web testing posture
 * (CLAUDE.md §8) this is a store/logic spec, no template rendering.
 */

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
    // Read-only identity.
    expect(view?.firstName).toBe('Pia');
    expect(view?.lastName).toBe('Lot');
    // Editable contact / address fields are populated (the T-18 fix).
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
        // PATCH responds with the /me projection (name only) in production;
        // the store re-reads via getMyPerson, so this value is irrelevant.
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
    // The view reflects the re-read contact, not the PATCH response.
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
