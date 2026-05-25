import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { FlightPrefsService } from './flight-prefs.service';

interface FakeRecord {
  sub: string;
  prefs: Record<string, unknown>;
}

/**
 * Tiny in-memory IndexedDB stand-in. The service is a thin IDB wrapper —
 * the unit-testable surface is namespace-by-sub and the merge inside
 * `recordTowPilot`. The shim covers only the API the service touches.
 */
class FakeStore {
  data = new Map<string, FakeRecord>();
  get(sub: string): FakeRequest<FakeRecord | undefined> {
    return new FakeRequest(this.data.get(sub));
  }
  put(rec: FakeRecord): FakeRequest<unknown> {
    this.data.set(rec.sub, rec);
    return new FakeRequest(undefined);
  }
  delete(sub: string): FakeRequest<unknown> {
    this.data.delete(sub);
    return new FakeRequest(undefined);
  }
  clear(): FakeRequest<unknown> {
    this.data.clear();
    return new FakeRequest(undefined);
  }
}

class FakeRequest<T> {
  result: T;
  onsuccess: (() => void) | null = null;
  onerror: (() => void) | null = null;
  constructor(result: T) {
    this.result = result;
    queueMicrotask(() => this.onsuccess?.());
  }
}

class FakeTransaction {
  constructor(private readonly store: FakeStore) {}
  objectStore(): FakeStore {
    return this.store;
  }
}

class FakeDb {
  closed = false;
  constructor(private readonly store: FakeStore) {}
  transaction(): FakeTransaction {
    return new FakeTransaction(this.store);
  }
  objectStoreNames = { contains: () => true };
  createObjectStore(): unknown {
    return {};
  }
  close(): void {
    this.closed = true;
  }
}

class FakeIndexedDB {
  private readonly store = new FakeStore();
  open(): FakeRequest<FakeDb> {
    return new FakeRequest(new FakeDb(this.store));
  }
  reset(): void {
    this.store.data.clear();
  }
}

describe('FlightPrefsService', () => {
  let fake: FakeIndexedDB;
  let svc: FlightPrefsService;

  beforeEach(() => {
    fake = new FakeIndexedDB();
    (globalThis as { indexedDB?: unknown }).indexedDB = fake;
    TestBed.configureTestingModule({});
    svc = TestBed.inject(FlightPrefsService);
  });

  afterEach(() => {
    delete (globalThis as { indexedDB?: unknown }).indexedDB;
  });

  it('returns empty prefs when nothing stored', async () => {
    expect(await svc.get('sub-1')).toEqual({});
  });

  it('persists per-sub prefs (no cross-sub leakage)', async () => {
    await svc.update('sub-A', 'lastStartLocation', 'loc-1');
    await svc.update('sub-B', 'lastStartLocation', 'loc-2');
    expect((await svc.get('sub-A')).lastStartLocation).toBe('loc-1');
    expect((await svc.get('sub-B')).lastStartLocation).toBe('loc-2');
  });

  it('merges towPilotByAircraftId map without dropping prior entries', async () => {
    await svc.recordTowPilot('sub-1', 'ac-a', 'pilot-1');
    await svc.recordTowPilot('sub-1', 'ac-b', 'pilot-2');
    const prefs = await svc.get('sub-1');
    expect(prefs.towPilotByAircraftId).toEqual({ 'ac-a': 'pilot-1', 'ac-b': 'pilot-2' });
  });

  it('overwrites existing tow-pilot for the same aircraft', async () => {
    await svc.recordTowPilot('sub-1', 'ac-a', 'pilot-1');
    await svc.recordTowPilot('sub-1', 'ac-a', 'pilot-9');
    expect((await svc.get('sub-1')).towPilotByAircraftId).toEqual({ 'ac-a': 'pilot-9' });
  });

  it('clear(sub) wipes only that sub', async () => {
    await svc.update('sub-A', 'lastStartLocation', 'loc-1');
    await svc.update('sub-B', 'lastStartLocation', 'loc-2');
    await svc.clear('sub-A');
    expect(await svc.get('sub-A')).toEqual({});
    expect((await svc.get('sub-B')).lastStartLocation).toBe('loc-2');
  });

  it('clearAll wipes every sub', async () => {
    await svc.update('sub-A', 'lastStartLocation', 'loc-1');
    await svc.update('sub-B', 'lastStartLocation', 'loc-2');
    await svc.clearAll();
    expect(await svc.get('sub-A')).toEqual({});
    expect(await svc.get('sub-B')).toEqual({});
  });

  it('degrades gracefully when IndexedDB is unavailable', async () => {
    delete (globalThis as { indexedDB?: unknown }).indexedDB;
    expect(await svc.get('sub-x')).toEqual({});
    await svc.update('sub-x', 'lastStartLocation', 'loc-z'); // no throw
    await svc.clear('sub-x'); // no throw
  });
});
