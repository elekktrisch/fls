import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, throwError, of } from 'rxjs';

import { HelloService } from '@api/generated/hello/hello.service';
import type { HelloResponse } from '@api/generated/model';

import { MUTATION_BUS, type MutationEvent } from '../../core/mutation-bus/mutation-bus';
import { HelloStore } from './hello.store';

const sample: HelloResponse = {
  message: 'hello',
  timestamp: '2026-05-17T10:00:00Z',
};

function helloServiceStub(stream: () => Observable<HelloResponse>): HelloService {
  // Cast through `unknown` to ignore the generic overload signatures — tests
  // only invoke the no-arg body variant.
  return { hello: stream as HelloService['hello'] } as unknown as HelloService;
}

function configure(service: HelloService): Subject<MutationEvent> {
  const bus = new Subject<MutationEvent>();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: bus },
      { provide: HelloService, useValue: service },
    ],
  });
  return bus;
}

describe('HelloStore', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('initialises empty with isLoading false and no error', () => {
    configure(helloServiceStub(() => of(sample)));
    const store = TestBed.inject(HelloStore);

    // onInit triggers loadHello; synchronous `of(sample)` resolves before this assertion.
    expect(store.items()).toEqual([sample]);
    expect(store.isLoading()).toBe(false);
    expect(store.loadError()).toBeNull();
    expect(store.offline()).toBe(false);
    expect(store.hasError()).toBe(false);
    expect(store.isEmpty()).toBe(false);
    expect(store.pageCount()).toBe(1);
    expect(store.showAdvanced()).toBe(false);
  });

  it('loadHello populates items + lastRefreshedAt on success', () => {
    configure(helloServiceStub(() => of(sample)));
    const store = TestBed.inject(HelloStore);

    expect(store.items()).toEqual([sample]);
    expect(store.lastRefreshedAt()).not.toBeNull();
  });

  it('loadHello sets loadError on non-zero HTTP failure', () => {
    const err = new HttpErrorResponse({ status: 500, statusText: 'Server Error' });
    configure(helloServiceStub(() => throwError(() => err)));
    const store = TestBed.inject(HelloStore);

    expect(store.isLoading()).toBe(false);
    expect(store.loadError()).not.toBeNull();
    expect(store.offline()).toBe(false);
    expect(store.hasError()).toBe(true);
  });

  it('loadHello marks store offline on status-0 HttpErrorResponse', () => {
    const err = new HttpErrorResponse({ status: 0 });
    configure(helloServiceStub(() => throwError(() => err)));
    const store = TestBed.inject(HelloStore);

    expect(store.offline()).toBe(true);
    expect(store.isLoading()).toBe(false);
    expect(store.loadError()).toBeNull();
  });

  it('setQuery toggles the showAdvanced computed signal', () => {
    configure(helloServiceStub(() => of(sample)));
    const store = TestBed.inject(HelloStore);

    expect(store.showAdvanced()).toBe(false);
    store.setQuery('foo');
    expect(store.showAdvanced()).toBe(true);
    store.setQuery('');
    expect(store.showAdvanced()).toBe(false);
  });

  it('clears items + loadError on session.logout via MUTATION_BUS', () => {
    const err = new HttpErrorResponse({ status: 500 });
    let nextResult: Observable<HelloResponse> = throwError(() => err);
    const bus = configure(helloServiceStub(() => nextResult));
    const store = TestBed.inject(HelloStore);

    expect(store.loadError()).not.toBeNull();
    // Flip the next-call result so a refresh would succeed if logout failed to clear.
    nextResult = of(sample);

    bus.next({ kind: 'session.logout' });

    expect(store.items()).toEqual([]);
    expect(store.loadError()).toBeNull();
    expect(store.offline()).toBe(false);
  });

  it('clears items on session.tenantSwitch via MUTATION_BUS', () => {
    const bus = configure(helloServiceStub(() => of(sample)));
    const store = TestBed.inject(HelloStore);

    expect(store.items()).toEqual([sample]);

    bus.next({ kind: 'session.tenantSwitch', clubId: 'club-2' });

    expect(store.items()).toEqual([]);
  });
});
