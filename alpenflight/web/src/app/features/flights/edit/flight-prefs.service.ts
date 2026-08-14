import { DestroyRef, inject, Injectable } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { MUTATION_BUS } from '@app/core/mutation-bus/mutation-bus';

const DB_NAME = 'af-flight-prefs';
const DB_VERSION = 1;
const STORE = 'prefs';

export interface FlightPrefs {
  lastStartLocation?: string;
  lastTowAircraftId?: string;
  towPilotByAircraftId?: Record<string, string>;
  lastGliderOutbound?: string;
  lastGliderInbound?: string;
  lastTowOutbound?: string;
  lastTowInbound?: string;
}

type PrefKey = keyof FlightPrefs;

interface PrefsRecord {
  sub: string;
  prefs: FlightPrefs;
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (typeof indexedDB === 'undefined') {
      reject(new Error('IndexedDB not available'));
      return;
    }
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE, { keyPath: 'sub' });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function asPromise<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

@Injectable({ providedIn: 'root' })
export class FlightPrefsService {
  constructor() {
    const bus = inject(MUTATION_BUS);
    const destroyRef = inject(DestroyRef);
    bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
      if (evt.kind === 'session.logout' || evt.kind === 'session.tenantSwitch') {
        void this.clearAll();
      }
    });
  }

  async get(sub: string): Promise<FlightPrefs> {
    try {
      const db = await openDb();
      try {
        const tx = db.transaction(STORE, 'readonly');
        const rec = await asPromise(tx.objectStore(STORE).get(sub));
        return (rec as PrefsRecord | undefined)?.prefs ?? {};
      } finally {
        db.close();
      }
    } catch {
      return {};
    }
  }

  async update<K extends PrefKey>(sub: string, key: K, value: FlightPrefs[K]): Promise<void> {
    try {
      const db = await openDb();
      try {
        const current = await this.get(sub);
        const next: FlightPrefs = { ...current, [key]: value };
        const tx = db.transaction(STORE, 'readwrite');
        await asPromise(tx.objectStore(STORE).put({ sub, prefs: next } satisfies PrefsRecord));
      } finally {
        db.close();
      }
    } catch {
    }
  }

  async recordTowPilot(sub: string, aircraftId: string, personId: string): Promise<void> {
    try {
      const current = await this.get(sub);
      const map = { ...(current.towPilotByAircraftId ?? {}), [aircraftId]: personId };
      await this.update(sub, 'towPilotByAircraftId', map);
    } catch {
    }
  }

  async clear(sub: string): Promise<void> {
    try {
      const db = await openDb();
      try {
        const tx = db.transaction(STORE, 'readwrite');
        await asPromise(tx.objectStore(STORE).delete(sub));
      } finally {
        db.close();
      }
    } catch {
    }
  }

  async clearAll(): Promise<void> {
    try {
      const db = await openDb();
      try {
        const tx = db.transaction(STORE, 'readwrite');
        await asPromise(tx.objectStore(STORE).clear());
      } finally {
        db.close();
      }
    } catch {
    }
  }
}
