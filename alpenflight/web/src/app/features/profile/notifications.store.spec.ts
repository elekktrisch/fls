import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { MeService } from '@api/generated/me/me.service';
import type {
  MeNotificationPrefsResponse,
  MeNotificationPrefsUpdateRequest,
} from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';

import { NotificationsStore } from './notifications.store';

const PREFS_BASE: MeNotificationPrefsResponse = {
  receiveFlightReports: true,
  receiveAircraftReservationNotifications: false,
  receivePlanningDayRoleReminder: true,
};

interface MeStubControls {
  get: () => Observable<MeNotificationPrefsResponse>;
  update: (req: MeNotificationPrefsUpdateRequest) => Observable<MeNotificationPrefsResponse>;
}

function meStub(controls: MeStubControls): MeService {
  return {
    getMyNotificationPrefs: ((options?: unknown) => {
      void options;
      return controls.get();
    }) as unknown as MeService['getMyNotificationPrefs'],
    updateMyNotificationPrefs: ((req: MeNotificationPrefsUpdateRequest, options?: unknown) => {
      void options;
      return controls.update(req);
    }) as unknown as MeService['updateMyNotificationPrefs'],
  } as unknown as MeService;
}

function configure(controls: MeStubControls): {
  store: InstanceType<typeof NotificationsStore>;
  busEvents: MutationEvent[];
} {
  const bus = new Subject<MutationEvent>();
  const busEvents: MutationEvent[] = [];
  bus.subscribe((e) => busEvents.push(e));

  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      NotificationsStore,
      { provide: MeService, useValue: meStub(controls) },
      { provide: MUTATION_BUS, useValue: bus },
    ],
  });
  const store = TestBed.inject(NotificationsStore);
  return { store, busEvents };
}

describe('NotificationsStore', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('hydrates the notification-prefs view from getMyNotificationPrefs, defaulting absent fields', () => {
    const { store } = configure({
      get: () => of({ receiveFlightReports: true }),
      update: () => of(PREFS_BASE),
    });
    store.load();
    const view = store.view();
    expect(view).not.toBeNull();
    expect(view?.receiveFlightReports).toBe(true);
    expect(view?.receiveAircraftReservationNotifications).toBe(false);
    expect(view?.receivePlanningDayRoleReminder).toBe(false);
    expect(store.canSave()).toBe(true);
  });

  it('surfaces an error on a failed load', () => {
    const err = new HttpErrorResponse({ status: 409 });
    const { store } = configure({
      get: () => throwError(() => err),
      update: () => of(PREFS_BASE),
    });
    store.load();
    expect(store.hasError()).toBe(true);
    expect(store.view()).toBeNull();
  });

  it('save() persists the pref edit, reflects the projection, and nudges the session via the bus', () => {
    const sent: MeNotificationPrefsUpdateRequest[] = [];
    const { store, busEvents } = configure({
      get: () => of(PREFS_BASE),
      update: (req) => {
        sent.push(req);
        return of({ ...PREFS_BASE, receiveAircraftReservationNotifications: true });
      },
    });
    store.load();

    store.save({
      receiveFlightReports: true,
      receiveAircraftReservationNotifications: true,
      receivePlanningDayRoleReminder: true,
    });

    expect(sent).toHaveLength(1);
    const req = sent[0]!;
    expect(req.receiveAircraftReservationNotifications).toBe(true);
    expect(store.view()?.receiveAircraftReservationNotifications).toBe(true);
    expect(store.savedOnce()).toBe(true);
    expect(store.isSaving()).toBe(false);
    expect(busEvents).toContainEqual({ kind: 'profile.updated' });
  });

  it('surfaces an error on a failed save without emitting the bus event', () => {
    const err = new HttpErrorResponse({ status: 400 });
    const { store, busEvents } = configure({
      get: () => of(PREFS_BASE),
      update: () => throwError(() => err),
    });
    store.load();
    store.save({ receiveFlightReports: false });
    expect(store.hasError()).toBe(true);
    expect(store.isSaving()).toBe(false);
    expect(busEvents).not.toContainEqual({ kind: 'profile.updated' });
  });
});
