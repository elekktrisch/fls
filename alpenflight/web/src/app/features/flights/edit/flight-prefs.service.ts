import { Injectable } from '@angular/core';

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

/**
 * OIDC-`sub`-scoped flight form preferences. Persisted in IndexedDB (not
 * localStorage — replaces the legacy raw-localStorage flight prefs per the
 * `web/CLAUDE.md` no-localStorage policy).
 *
 * Architectural intent ("Dexie-backed" in S-062c design): origin-scoped,
 * sub-keyed, wipe-on-logout / tenant-switch. Implemented as a thin native
 * IDB wrapper to avoid a new dependency; swap to Dexie when S-062h ships
 * the drafts store and the SW mutation queue.
 *
 * The service is best-effort: reads return `null` / empty on any IDB error,
 * writes silently swallow. Prefs are a UX convenience, not durable state.
 */
@Injectable({ providedIn: 'root' })
export class FlightPrefsService {
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
      // best-effort
    }
  }

  /**
   * Record the chosen tow-pilot per tow-aircraft. Matches legacy
   * `towPilotByAircraftId[aircraftId]` map (`FlightsController.js:350-352`).
   */
  async recordTowPilot(sub: string, aircraftId: string, personId: string): Promise<void> {
    try {
      const current = await this.get(sub);
      const map = { ...(current.towPilotByAircraftId ?? {}), [aircraftId]: personId };
      await this.update(sub, 'towPilotByAircraftId', map);
    } catch {
      // best-effort
    }
  }

  /** Wipe all prefs for the given sub. Called on logout + tenant-switch. */
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
      // best-effort
    }
  }

  /** Wipe ALL prefs across all subs. For tests + tenant-switch worst case. */
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
      // best-effort
    }
  }
}
