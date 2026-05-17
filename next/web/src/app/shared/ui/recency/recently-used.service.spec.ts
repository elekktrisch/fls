import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';

import { RecentlyUsedService } from './recently-used.service';

const STORAGE_KEY = 'af.recently-used.v1';
const DAY_MS = 24 * 60 * 60 * 1000;

describe('RecentlyUsedService', () => {
  beforeEach(() => {
    localStorage.removeItem(STORAGE_KEY);
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection()],
    });
  });

  afterEach(() => {
    localStorage.removeItem(STORAGE_KEY);
    TestBed.resetTestingModule();
  });

  it('returns an id recorded within the 7-day window', () => {
    const svc = TestBed.inject(RecentlyUsedService);
    const now = 1_700_000_000_000;
    svc.record('aircraft', 'HB-PCD', now);
    expect(svc.recent('aircraft', 7, now)).toEqual(['HB-PCD']);
  });

  it('excludes entries older than the window', () => {
    const svc = TestBed.inject(RecentlyUsedService);
    const now = 1_700_000_000_000;
    svc.record('aircraft', 'HB-OLD', now - 8 * DAY_MS);
    expect(svc.recent('aircraft', 7, now)).toEqual([]);
  });

  it('partitions entries by primitiveKey', () => {
    const svc = TestBed.inject(RecentlyUsedService);
    const now = 1_700_000_000_000;
    svc.record('aircraft', 'HB-A', now);
    expect(svc.recent('pilot', 7, now)).toEqual([]);
  });

  it('sorts most-recent first', () => {
    const svc = TestBed.inject(RecentlyUsedService);
    const now = 1_700_000_000_000;
    svc.record('aircraft', 'older', now - DAY_MS);
    svc.record('aircraft', 'newer', now);
    expect(svc.recent('aircraft', 7, now)).toEqual(['newer', 'older']);
  });

  it('evicts oldest when count exceeds 50', () => {
    const svc = TestBed.inject(RecentlyUsedService);
    const now = 1_700_000_000_000;
    for (let i = 0; i < 51; i++) {
      svc.record('aircraft', `id-${i}`, now - (50 - i) * 1000);
    }
    const ids = svc.recent('aircraft', 365, now);
    expect(ids.length).toBe(50);
    expect(ids).not.toContain('id-0');
    expect(ids).toContain('id-50');
  });

  it('persists across service instances via localStorage', () => {
    const first = TestBed.inject(RecentlyUsedService);
    first.record('pilot', 'p-1', 1_700_000_000_000);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection()],
    });
    const second = TestBed.inject(RecentlyUsedService);
    expect(second.recent('pilot', 7, 1_700_000_000_000)).toContain('p-1');
  });

  it('clear() wipes all entries', () => {
    const svc = TestBed.inject(RecentlyUsedService);
    svc.record('aircraft', 'x', 1_700_000_000_000);
    svc.clear();
    expect(svc.recent('aircraft', 7, 1_700_000_000_000)).toEqual([]);
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
  });
});
