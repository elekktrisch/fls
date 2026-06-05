import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { MeService } from '@api/generated/me/me.service';
import type { MePersonUpdateRequest, MeResponse } from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';

import { PersonalStore } from './personal.store';

/**
 * Logic test for the Personal-tab store (J-4 T-07): the `/me` load hydrates the
 * read-only name fields, `save()` round-trips through `updateMyPerson` and
 * reflects the persisted projection, and a `profile.updated` event is emitted so
 * the session re-reads `/me`. The form DOM + the real PATCH wiring are proven by
 * the real-idp e2e (`profile/self-edit.spec.ts`). Per web testing posture
 * (CLAUDE.md §8) this is a store/logic spec, no template rendering.
 */

const ME_BASE: MeResponse = {
  id: 'u-1',
  personId: 'pn-1',
  clubId: 'clb-1',
  roles: ['PILOT'],
  firstName: 'Pia',
  lastName: 'Lot',
  email: 'pia@club.example',
  username: 'pilot1@example.com',
  friendlyName: 'Pia L.',
  phoneNumber: '+41 11 111',
  languageId: 'lang-de',
  languageCode: 'de',
};

interface MeStubControls {
  get1: () => Observable<MeResponse>;
  update: (req: MePersonUpdateRequest) => Observable<MeResponse>;
}

function meStub(controls: MeStubControls): MeService {
  return {
    get1: ((options?: unknown) => {
      void options;
      return controls.get1();
    }) as MeService['get1'],
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

  it('loads the caller name fields from /me when load() is called', () => {
    const { store } = configure({ get1: () => of(ME_BASE), update: () => of(ME_BASE) });
    store.load();
    const view = store.view();
    expect(view).not.toBeNull();
    expect(view?.firstName).toBe('Pia');
    expect(view?.lastName).toBe('Lot');
    expect(store.canSave()).toBe(true);
  });

  it('surfaces an error on a failed /me load', () => {
    const err = new HttpErrorResponse({ status: 500 });
    const { store } = configure({ get1: () => throwError(() => err), update: () => of(ME_BASE) });
    store.load();
    expect(store.hasError()).toBe(true);
    expect(store.view()).toBeNull();
  });

  it('save() persists the contact edit, reflects the projection, and nudges the session via the bus', () => {
    const sent: MePersonUpdateRequest[] = [];
    const { store, busEvents } = configure({
      get1: () => of(ME_BASE),
      update: (req) => {
        sent.push(req);
        return of(ME_BASE);
      },
    });
    store.load();

    store.save({
      addressLine1: 'Flugplatzstrasse 1',
      city: 'Bern',
      privatePhone: '+41 31 000',
      businessPhone: '+41 31 999',
      birthday: '1990-05-01',
    });

    expect(sent).toHaveLength(1);
    const req = sent[0]!;
    expect(req.addressLine1).toBe('Flugplatzstrasse 1');
    expect(req.city).toBe('Bern');
    expect(store.savedOnce()).toBe(true);
    expect(store.isSaving()).toBe(false);
    expect(busEvents).toContainEqual({ kind: 'profile.updated' });
  });

  it('surfaces an error on a failed save without emitting the bus event', () => {
    const err = new HttpErrorResponse({ status: 400 });
    const { store, busEvents } = configure({
      get1: () => of(ME_BASE),
      update: () => throwError(() => err),
    });
    store.load();
    store.save({ city: 'X' });
    expect(store.hasError()).toBe(true);
    expect(store.isSaving()).toBe(false);
    expect(busEvents).not.toContainEqual({ kind: 'profile.updated' });
  });
});
