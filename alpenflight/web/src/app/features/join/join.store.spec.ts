import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { JoinRequestsService } from '@api/generated/join-requests/join-requests.service';
import type { JoinRequestResponse, SubmitJoinRequest } from '@api/generated/model';

import { JoinStore } from './join.store';

const pending: JoinRequestResponse = {
  id: 'jr-1',
  clubId: 'club-1',
  status: 'PENDING',
  note: 'please',
  createdOn: '2026-06-23T10:00:00Z',
};

interface ApiStubs {
  submit: (req: SubmitJoinRequest) => Observable<JoinRequestResponse>;
  withdraw: (id: string) => Observable<JoinRequestResponse>;
  myJoinRequest: () => Observable<JoinRequestResponse | null>;
}

function serviceStub(stubs: Partial<ApiStubs>): JoinRequestsService {
  const api = {
    submit: ((req: SubmitJoinRequest, options?: unknown) => {
      void options;
      return (stubs.submit ?? (() => of(pending)))(req);
    }) as JoinRequestsService['submit'],
    withdraw: ((id: string, options?: unknown) => {
      void options;
      return (stubs.withdraw ?? (() => of({ ...pending, status: 'WITHDRAWN' })))(id);
    }) as JoinRequestsService['withdraw'],
    myJoinRequest: ((options?: unknown) => {
      void options;
      return (stubs.myJoinRequest ?? (() => of(null)))();
    }) as JoinRequestsService['myJoinRequest'],
  };
  return api as unknown as JoinRequestsService;
}

function configure(api: JoinRequestsService): InstanceType<typeof JoinStore> {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: JoinRequestsService, useValue: api },
      JoinStore,
    ],
  });
  return TestBed.inject(JoinStore);
}

function httpError(
  status: number,
  body?: unknown,
  headers?: Record<string, string>,
): HttpErrorResponse {
  return new HttpErrorResponse({
    status,
    statusText: '',
    error: body,
    ...(headers ? { headers: new HttpHeaders(headers) } : {}),
  });
}

describe('JoinStore', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('submit success holds the pending request and clears any error', () => {
    let sent: SubmitJoinRequest | null = null;
    const store = configure(
      serviceStub({
        submit: (req) => {
          sent = req;
          return of(pending);
        },
      }),
    );

    store.submit('ABCD2345', 'please');

    expect(sent).toEqual({ joinCode: 'ABCD2345', note: 'please' });
    expect(store.request()).toEqual(pending);
    expect(store.submitError()).toBeNull();
    expect(store.isSubmitting()).toBe(false);
  });

  it('submit maps 404 to an unknown-code error', () => {
    const store = configure(
      serviceStub({ submit: () => throwError(() => httpError(404, { detail: 'no such code' })) }),
    );

    store.submit('NOPE2345');

    expect(store.submitError()?.kind).toBe('unknown-code');
    expect(store.request()).toBeNull();
    expect(store.isSubmitting()).toBe(false);
  });

  it('submit maps 409 to an already-member error', () => {
    const store = configure(
      serviceStub({
        submit: () => throwError(() => httpError(409, { detail: 'already in a club' })),
      }),
    );

    store.submit('ABCD2345');

    expect(store.submitError()?.kind).toBe('already-member');
    expect(store.request()).toBeNull();
  });

  it('submit maps 429 to a rate-limited error carrying Retry-After seconds', () => {
    const store = configure(
      serviceStub({
        submit: () =>
          throwError(() => httpError(429, { detail: 'too many' }, { 'Retry-After': '120' })),
      }),
    );

    store.submit('ABCD2345');

    const err = store.submitError();
    expect(err?.kind).toBe('rate-limited');
    expect(err?.retryAfterSeconds).toBe(120);
    expect(store.request()).toBeNull();
  });

  it('loadMine populates the request when one exists', () => {
    const store = configure(serviceStub({ myJoinRequest: () => of(pending) }));

    store.loadMine();

    expect(store.request()).toEqual(pending);
  });

  it('loadMine leaves the request null on a 204 (empty body)', () => {
    const store = configure(serviceStub({ myJoinRequest: () => of(null) }));

    store.loadMine();

    expect(store.request()).toBeNull();
  });

  it('withdraw clears the held request so the pilot can re-submit', () => {
    let withdrawnId: string | null = null;
    const store = configure(
      serviceStub({
        myJoinRequest: () => of(pending),
        withdraw: (id) => {
          withdrawnId = id;
          return of({ ...pending, status: 'WITHDRAWN' });
        },
      }),
    );
    store.loadMine();
    expect(store.request()).toEqual(pending);

    store.withdraw('jr-1');

    expect(withdrawnId).toBe('jr-1');
    expect(store.request()).toBeNull();
  });
});
