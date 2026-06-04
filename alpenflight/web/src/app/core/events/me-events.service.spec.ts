import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import type { EventSourceMessage } from '@microsoft/fetch-event-source';
import { patchState } from '@ngrx/signals';
import { unprotected } from '@ngrx/signals/testing';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { Observable, Subject, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { MUTATION_BUS, type MutationEvent } from '../mutation-bus/mutation-bus';
import { SessionStore, type User } from '../session/session.store';

import { classifyOpen, reconnectDelayMs, toMeEvent } from './me-events.core';
import { MeEventsService, SSE_TRANSPORT, type SseTransport } from './me-events.service';

const pilot: User = {
  id: 'b9c0e2a5-1d3f-4a2e-9c6e-22f3a0c0a001',
  username: 'pilot1',
  email: 'pilot1@example.com',
  firstName: 'Petra',
  lastName: 'Pilot',
  clubId: '019e30c3-2c00-7001-8000-000000000001',
  personId: null,
  roles: ['PILOT'],
};

/** Captures the single live `open(...)` call so the test can drive its callbacks. */
interface CapturedOpen {
  url: string;
  headers: Record<string, string>;
  signal: AbortSignal;
  onopen: (r: { status: number; headers: { get(n: string): string | null } }) => Promise<void>;
  onmessage: (frame: EventSourceMessage) => void;
  onerror: (err: unknown) => number | void;
}

function fakeTransport(): SseTransport & { opens: CapturedOpen[]; last(): CapturedOpen } {
  const opens: CapturedOpen[] = [];
  return {
    opens,
    last() {
      const o = opens.at(-1);
      if (!o) {
        throw new Error('transport.open was never called');
      }
      return o;
    },
    open(url, init) {
      opens.push({
        url,
        headers: init.headers,
        signal: init.signal,
        onopen: init.onopen,
        onmessage: init.onmessage,
        onerror: init.onerror,
      });
      // A real stream stays open — never resolve unless aborted.
      return new Promise<void>((_, reject) => {
        init.signal.addEventListener('abort', () =>
          reject(new DOMException('aborted', 'AbortError')),
        );
      });
    },
  };
}

function frame(event: string, data: string, id = ''): EventSourceMessage {
  return { event, data, id };
}

function eventStreamResponse(status = 200): {
  status: number;
  headers: { get(n: string): string | null };
} {
  return {
    status,
    headers: { get: (n) => (n.toLowerCase() === 'content-type' ? 'text/event-stream' : null) },
  };
}

function setup(getAccessToken: () => Observable<string> = () => of('tok-123')) {
  const transport = fakeTransport();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MUTATION_BUS, useValue: new Subject<MutationEvent>() },
      { provide: SSE_TRANSPORT, useValue: transport },
      { provide: OidcSecurityService, useValue: { getAccessToken } },
    ],
  });
  const store = TestBed.inject(SessionStore);
  const service = TestBed.inject(MeEventsService);
  return { transport, store, service };
}

// Flush the microtask queue so the async openStream() (awaits getAccessToken
// then calls transport.open) reaches transport.open after the effect fires.
const flushMicrotasks = () => Promise.resolve().then(() => Promise.resolve());

afterEach(() => TestBed.resetTestingModule());

describe('me-events.core', () => {
  it('toMeEvent decodes a kind+data frame and drops keep-alive (no event) frames', () => {
    expect(toMeEvent({ event: 'flight.created', data: '{"id":"f1"}', id: '7' })).toEqual({
      kind: 'flight.created',
      data: '{"id":"f1"}',
      id: '7',
    });
    expect(toMeEvent({ data: ':heartbeat' })).toBeNull();
    expect(toMeEvent({ event: '   ' })).toBeNull();
  });

  it('classifyOpen: 200 event-stream ok, 401/403 fatal, 429/5xx retriable, 200 wrong-type fatal', () => {
    expect(classifyOpen(200, 'text/event-stream')).toBe('ok');
    expect(classifyOpen(200, 'text/html')).toBe('fatal');
    expect(classifyOpen(401, null)).toBe('fatal');
    expect(classifyOpen(403, null)).toBe('fatal');
    expect(classifyOpen(429, null)).toBe('retriable');
    expect(classifyOpen(503, null)).toBe('retriable');
  });

  it('reconnectDelayMs grows exponentially and is bounded by the cap', () => {
    expect(reconnectDelayMs(1, 1000, 30000)).toBe(1000);
    expect(reconnectDelayMs(2, 1000, 30000)).toBe(2000);
    expect(reconnectDelayMs(3, 1000, 30000)).toBe(4000);
    expect(reconnectDelayMs(99, 1000, 30000)).toBe(30000);
  });
});

describe('MeEventsService', () => {
  it('does NOT open the stream while unauthenticated', async () => {
    const { transport, store } = setup();
    patchState(unprotected(store), { sessionStatus: 'idle' });
    TestBed.tick();
    await flushMicrotasks();

    expect(transport.opens).toHaveLength(0);
  });

  it('opens the stream with a Bearer header once the session authenticates', async () => {
    const { transport, store } = setup(() => of('tok-abc'));
    store.login(pilot, pilot.clubId);
    TestBed.tick();
    await flushMicrotasks();

    expect(transport.opens).toHaveLength(1);
    expect(transport.last().url).toBe('/api/v1/me/events');
    expect(transport.last().headers['Authorization']).toBe('Bearer tok-abc');
  });

  it('delivers a flight.created frame to a subscriber of that kind only', async () => {
    const { transport, store, service } = setup();
    const flightSeen: string[] = [];
    const otherSeen: string[] = [];
    service.on('flight.created').subscribe((e) => flightSeen.push(e.data));
    service.on('reservation.created').subscribe((e) => otherSeen.push(e.data));

    store.login(pilot, pilot.clubId);
    TestBed.tick();
    await flushMicrotasks();

    await transport.last().onopen(eventStreamResponse(200));
    transport.last().onmessage(frame('flight.created', '{"id":"f9"}'));

    expect(flightSeen).toEqual(['{"id":"f9"}']);
    expect(otherSeen).toEqual([]);
  });

  it('keep-alive frames (no event field) reach no subscriber', async () => {
    const { transport, store, service } = setup();
    const seen: string[] = [];
    service.on('flight.created').subscribe((e) => seen.push(e.data));

    store.login(pilot, pilot.clubId);
    TestBed.tick();
    await flushMicrotasks();
    await transport.last().onopen(eventStreamResponse(200));
    transport.last().onmessage(frame('', ':keep-alive'));

    expect(seen).toEqual([]);
  });

  it('aborts the stream on logout and does not re-open', async () => {
    const { transport, store } = setup();
    store.login(pilot, pilot.clubId);
    TestBed.tick();
    await flushMicrotasks();
    expect(transport.last().signal.aborted).toBe(false);

    store.logout();
    TestBed.tick();
    await flushMicrotasks();

    expect(transport.last().signal.aborted).toBe(true);
    // No second open after the intentional close.
    expect(transport.opens).toHaveLength(1);
  });

  it('after an intentional close, onerror rethrows (stops the library retry loop)', async () => {
    const { transport, store } = setup();
    store.login(pilot, pilot.clubId);
    TestBed.tick();
    await flushMicrotasks();
    const open = transport.last();

    store.logout();
    TestBed.tick();
    await flushMicrotasks();

    // The library would call onerror after our abort; an intentional close
    // must rethrow (return undefined ⇒ retry; throw ⇒ stop).
    expect(() => open.onerror(new Error('aborted'))).toThrow();
  });

  it('reconnects (returns a bounded backoff) on a transient error', async () => {
    const { transport, store } = setup();
    store.login(pilot, pilot.clubId);
    TestBed.tick();
    await flushMicrotasks();
    await transport.last().onopen(eventStreamResponse(200));

    // Transient drop → onerror returns a numeric delay (do not throw).
    const delay1 = transport.last().onerror(new Error('network blip'));
    const delay2 = transport.last().onerror(new Error('network blip'));

    expect(delay1).toBe(reconnectDelayMs(1));
    expect(delay2).toBe(reconnectDelayMs(2));
  });

  it('does NOT reconnect after a 401 open rejection (fatal — onopen throws, onerror rethrows)', async () => {
    const { transport, store } = setup();
    store.login(pilot, pilot.clubId);
    TestBed.tick();
    await flushMicrotasks();
    const open = transport.last();

    // 401 onopen → the service throws a fatal error.
    await expect(open.onopen({ status: 401, headers: { get: () => null } })).rejects.toThrow();
    // Feeding that fatal error back through onerror rethrows it (stops retry).
    let fatal: unknown;
    await open.onopen({ status: 401, headers: { get: () => null } }).catch((e) => (fatal = e));
    expect(() => open.onerror(fatal)).toThrow();
  });

  it('does not open when no access token resolves', async () => {
    const fail = vi.fn(() => new Observable<string>((sub) => sub.error(new Error('no token'))));
    const { transport, store } = setup(fail);
    store.login(pilot, pilot.clubId);
    TestBed.tick();
    await flushMicrotasks();

    expect(transport.opens).toHaveLength(0);
  });
});
