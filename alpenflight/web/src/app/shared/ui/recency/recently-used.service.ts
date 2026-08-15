import { Injectable } from '@angular/core';

const STORAGE_KEY = 'af.recently-used.v1';
const MAX_ENTRIES_PER_KEY = 50;

type Entries = Record<string, Record<string, number>>;

@Injectable({ providedIn: 'root' })
export class RecentlyUsedService {
  #entries: Entries = this.#read();

  record(primitiveKey: string, id: string | number, now: number = Date.now()): void {
    const bucket = (this.#entries[primitiveKey] ??= {});
    bucket[String(id)] = now;
    this.#evictIfNeeded(bucket);
    this.#writeBestEffort();
  }

  recent(primitiveKey: string, windowDays = 7, now: number = Date.now()): readonly string[] {
    const bucket = this.#entries[primitiveKey];
    if (!bucket) return [];
    const cutoff = now - windowDays * 24 * 60 * 60 * 1000;
    return Object.entries(bucket)
      .filter(([, ts]) => ts >= cutoff)
      .sort(([, a], [, b]) => b - a)
      .map(([id]) => id);
  }

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

  #writeBestEffort(): void {
    if (!this.#hasStorage()) return;
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(this.#entries));
    } catch {
      return;
    }
  }

  #hasStorage(): boolean {
    return typeof globalThis !== 'undefined' && 'localStorage' in globalThis;
  }
}
