import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MeService } from '@api/generated/me/me.service';
import type { MeProfileUpdateRequest, MeResponse } from '@api/generated/model';
import { type AppLocale, LANGUAGE_BY_LOCALE, LocaleService } from '@shared/ui/locale';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';

import { AccountStore } from './account.store';

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
  languageId: LANGUAGE_BY_LOCALE.de,
  languageCode: 'de',
};

interface MeStubControls {
  get1: () => Observable<MeResponse>;
  update: (req: MeProfileUpdateRequest) => Observable<MeResponse>;
}

function meStub(controls: MeStubControls): MeService {
  return {
    get1: ((options?: unknown) => {
      void options;
      return controls.get1();
    }) as MeService['get1'],
    updateMyProfile: ((req: MeProfileUpdateRequest, options?: unknown) => {
      void options;
      return controls.update(req);
    }) as unknown as MeService['updateMyProfile'],
  } as unknown as MeService;
}

function configure(controls: MeStubControls): {
  store: InstanceType<typeof AccountStore>;
  setLocale: ReturnType<typeof vi.fn>;
  busEvents: MutationEvent[];
  current: { value: AppLocale };
} {
  const current = { value: 'de' as AppLocale };
  const setLocale = vi.fn((l: AppLocale) => {
    current.value = l;
  });
  const localeStub = {
    current: () => current.value,
    set: setLocale,
  } as unknown as LocaleService;
  const bus = new Subject<MutationEvent>();
  const busEvents: MutationEvent[] = [];
  bus.subscribe((e) => busEvents.push(e));

  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      AccountStore,
      { provide: MeService, useValue: meStub(controls) },
      { provide: LocaleService, useValue: localeStub },
      { provide: MUTATION_BUS, useValue: bus },
    ],
  });
  const store = TestBed.inject(AccountStore);
  return { store, setLocale, busEvents, current };
}

describe('AccountStore', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('loads the caller account fields from /me when load() is called', () => {
    const { store } = configure({ get1: () => of(ME_BASE), update: () => of(ME_BASE) });
    store.load();
    const view = store.view();
    expect(view).not.toBeNull();
    expect(view?.friendlyName).toBe('Pia L.');
    expect(view?.notificationEmail).toBe('pia@club.example');
    expect(view?.phoneNumber).toBe('+41 11 111');
    expect(view?.languageId).toBe(LANGUAGE_BY_LOCALE.de);
    expect(view?.username).toBe('pilot1@example.com');
    expect(view?.clubId).toBe('clb-1');
    expect(store.canSave()).toBe(true);
  });

  it('surfaces an error on a failed /me load', () => {
    const err = new HttpErrorResponse({ status: 500 });
    const { store } = configure({ get1: () => throwError(() => err), update: () => of(ME_BASE) });
    store.load();
    expect(store.hasError()).toBe(true);
    expect(store.view()).toBeNull();
  });

  it('save() persists, reflects the returned projection, and nudges the session via the bus', () => {
    const saved: MeResponse = {
      ...ME_BASE,
      friendlyName: 'New Name',
      email: 'new@club.example',
      phoneNumber: '+41 22 222',
    };
    let sentReq: MeProfileUpdateRequest | null = null;
    const { store, busEvents } = configure({
      get1: () => of(ME_BASE),
      update: (req) => {
        sentReq = req;
        return of(saved);
      },
    });

    store.save({
      friendlyName: 'New Name',
      notificationEmail: 'new@club.example',
      phoneNumber: '+41 22 222',
      languageId: LANGUAGE_BY_LOCALE.de,
    });

    expect(sentReq).not.toBeNull();
    expect(store.view()?.friendlyName).toBe('New Name');
    expect(store.view()?.notificationEmail).toBe('new@club.example');
    expect(store.savedOnce()).toBe(true);
    expect(store.isSaving()).toBe(false);
    expect(busEvents).toContainEqual({ kind: 'profile.updated' });
  });

  it('flips the SPA locale when the saved language changes to a supported locale', () => {
    const savedFr: MeResponse = {
      ...ME_BASE,
      languageId: LANGUAGE_BY_LOCALE.fr,
      languageCode: 'fr',
    };
    const { store, setLocale, current } = configure({
      get1: () => of(ME_BASE),
      update: () => of(savedFr),
    });

    store.save({
      friendlyName: 'Pia L.',
      notificationEmail: 'pia@club.example',
      languageId: LANGUAGE_BY_LOCALE.fr,
    });

    expect(setLocale).toHaveBeenCalledWith('fr');
    expect(current.value).toBe('fr');
  });

  it('does not flip the locale when the language is unchanged', () => {
    const { store, setLocale } = configure({ get1: () => of(ME_BASE), update: () => of(ME_BASE) });
    store.save({
      friendlyName: 'Pia L.',
      notificationEmail: 'pia@club.example',
      languageId: LANGUAGE_BY_LOCALE.de,
    });
    expect(setLocale).not.toHaveBeenCalled();
  });

  it('surfaces an error on a failed save without flipping locale', () => {
    const err = new HttpErrorResponse({ status: 400 });
    const { store, setLocale } = configure({
      get1: () => of(ME_BASE),
      update: () => throwError(() => err),
    });
    store.save({
      friendlyName: 'X',
      notificationEmail: 'x@club.example',
      languageId: LANGUAGE_BY_LOCALE.fr,
    });
    expect(store.hasError()).toBe(true);
    expect(store.isSaving()).toBe(false);
    expect(setLocale).not.toHaveBeenCalled();
  });
});
