import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { type Observable, Subject, of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';

import { DeliveriesService } from '@api/generated/deliveries/deliveries.service';
import type { DeliveryDetail, DeliveryPage } from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../../core/mutation-bus/mutation-bus';
import { DeliveriesStore } from './deliveries.store';

const EMPTY_PAGE: DeliveryPage = { items: [], totalRows: 0, pageStart: 0, pageSize: 20 };

interface ServiceStub {
  page?: () => Observable<DeliveryPage>;
  create?: () => Observable<DeliveryDetail[]>;
  remove?: () => Observable<void>;
}

function configure(stub: ServiceStub): void {
  const service = {
    pageDeliveries: () => stub.page?.() ?? of(EMPTY_PAGE),
    createDeliveries: () => stub.create?.() ?? of([] as DeliveryDetail[]),
    deleteDelivery: () => stub.remove?.() ?? of(undefined),
  } as unknown as DeliveriesService;
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: new Subject<MutationEvent>() },
      { provide: DeliveriesService, useValue: service },
    ],
  });
}

describe('DeliveriesStore write actions', () => {
  it('createDeliveries refreshes the current page on success', () => {
    let pageCalls = 0;
    configure({
      page: () => {
        pageCalls += 1;
        return of(EMPTY_PAGE);
      },
      create: () => of([] as DeliveryDetail[]),
    });
    const store = TestBed.inject(DeliveriesStore);
    const before = pageCalls;
    store.createDeliveries();
    expect(pageCalls).toBeGreaterThan(before);
    expect(store.createError()).toBeNull();
  });

  it('createDeliveries surfaces an error message on failure', () => {
    const err = new HttpErrorResponse({
      status: 403,
      statusText: 'Forbidden',
      error: { message: 'Not allowed.' },
    });
    configure({ create: () => throwError(() => err) });
    const store = TestBed.inject(DeliveriesStore);
    store.createDeliveries();
    expect(store.createError()).toBe('Not allowed.');
  });

  it('deleteDelivery refreshes the current page on success', () => {
    let pageCalls = 0;
    configure({
      page: () => {
        pageCalls += 1;
        return of(EMPTY_PAGE);
      },
      remove: () => of(undefined),
    });
    const store = TestBed.inject(DeliveriesStore);
    const before = pageCalls;
    store.deleteDelivery('11111111-1111-1111-1111-111111111111');
    expect(pageCalls).toBeGreaterThan(before);
    expect(store.deleteError()).toBeNull();
  });

  it('deleteDelivery surfaces a 409 conflict (shared-flight guard) without crashing', () => {
    const err = new HttpErrorResponse({
      status: 409,
      statusText: 'Conflict',
      error: { key: 'delivery.flight.shared' },
    });
    configure({ remove: () => throwError(() => err) });
    const store = TestBed.inject(DeliveriesStore);
    store.deleteDelivery('11111111-1111-1111-1111-111111111111');
    expect(store.deleteError()).not.toBeNull();
  });
});
