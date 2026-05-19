// Per-user recency cache is a deliberate localStorage consumer; the
// shared/ui/recency/ folder is allowlisted in eslint.config.mjs (S-008 design).
// The other sanctioned consumer is the S-021 auth-token storage seam.

import { Injectable } from '@angular/core';

const STORAGE_KEY = 'af.recently-used.v1';
const MAX_ENTRIES_PER_KEY = 50;

type Entries = Record<string, Record<string, number>>;

/**
 * Per-primitive recency cache, persisted in `localStorage`. Consumed by
 * `<af-autocomplete>` to surface a "Recently used" group at the top of its
 * dropdown.
 *
 * Bounded at 50 entries per primitiveKey; LRU eviction (oldest by timestamp).
 * Reads are signal-free — the consumer reads on dropdown open, no
 * subscription needed.
 */
@Injectable({ providedIn: 'root' })
export class RecentlyUsedService {
  #entries: Entries = this.#read();

  /** Record an id under the given primitiveKey with the current timestamp. */
  record(primitiveKey: string, id: string | number, now: number = Date.now()): void {
    const bucket = (this.#entries[primitiveKey] ??= {});
    bucket[String(id)] = now;
    this.#evictIfNeeded(bucket);
    this.#write();
  }

  /**
   * Return the ids recorded under primitiveKey within the last `windowDays`,
   * most-recent-first.
   */
  recent(primitiveKey: string, windowDays = 7, now: number = Date.now()): readonly string[] {
    const bucket = this.#entries[primitiveKey];
    if (!bucket) return [];
    const cutoff = now - windowDays * 24 * 60 * 60 * 1000;
    return Object.entries(bucket)
      .filter(([, ts]) => ts >= cutoff)
      .sort(([, a], [, b]) => b - a)
      .map(([id]) => id);
  }

  /** Reset everything — intended for tests + the future `logout()` flow. */
  clear(): void {
    this.#entries = {};
    if (this.#hasStorage()) localStorage.removeItem(STORAGE_KEY);
  }

  #evictIfNeeded(bucket: Record<string, number>): void {
    const entries = Object.entries(bucket);
    if (entries.length <= MAX_ENTRIES_PER_KEY) return;
    entries
      .sort(([, a], [, b]) => a - b)
      .slice(0, entries.length - MAX_ENTRIES_PER_KEY)
      .forEach(([id]) => delete bucket[id]);
  }

  #read(): Entries {
    if (!this.#hasStorage()) return {};
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      return raw ? (JSON.parse(raw) as Entries) : {};
    } catch {
      return {};
    }
  }

  #write(): void {
    if (!this.#hasStorage()) return;
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(this.#entries));
    } catch {
      // Quota exceeded or storage disabled — silently skip; recency is best-effort.
    }
  }

  #hasStorage(): boolean {
    return typeof globalThis !== 'undefined' && 'localStorage' in globalThis;
  }
}
