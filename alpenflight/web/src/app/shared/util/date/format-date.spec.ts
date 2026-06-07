import { describe, expect, it } from 'vitest';

import {
  formatDdMmYyyy,
  formatIsoDateDdMmYyyy,
  isoDateFromLocal,
  localDateFromIso,
} from './format-date';

describe('formatDdMmYyyy', () => {
  it('formats a Date as DD.MM.YYYY with zero-padding from local fields', () => {
    // 7 March 2026, local — single-digit day + month must zero-pad.
    expect(formatDdMmYyyy(new Date(2026, 2, 7))).toBe('07.03.2026');
  });

  it('formats a two-digit day/month without extra padding', () => {
    expect(formatDdMmYyyy(new Date(2026, 11, 21))).toBe('21.12.2026');
  });

  it('accepts an epoch-ms number', () => {
    const epoch = new Date(2026, 4, 21).getTime();
    expect(formatDdMmYyyy(epoch)).toBe('21.05.2026');
  });

  it('returns empty string for null / undefined / empty / unparseable input', () => {
    expect(formatDdMmYyyy(null)).toBe('');
    expect(formatDdMmYyyy(undefined)).toBe('');
    expect(formatDdMmYyyy('')).toBe('');
    expect(formatDdMmYyyy('not-a-date')).toBe('');
  });
});

describe('formatIsoDateDdMmYyyy', () => {
  it('reorders a YYYY-MM-DD string to DD.MM.YYYY without a Date round-trip', () => {
    expect(formatIsoDateDdMmYyyy('2026-05-21')).toBe('21.05.2026');
  });

  it('does not drift the day across the UTC boundary (string-only, no new Date)', () => {
    // The whole point: a date-only string parsed via `new Date(...)` is UTC
    // midnight and can render a day earlier in a negative-offset zone. Pure
    // string reorder keeps the calendar date exactly as authored.
    expect(formatIsoDateDdMmYyyy('2026-01-01')).toBe('01.01.2026');
  });

  it('returns empty string for null / undefined / non-10-char input', () => {
    expect(formatIsoDateDdMmYyyy(null)).toBe('');
    expect(formatIsoDateDdMmYyyy(undefined)).toBe('');
    expect(formatIsoDateDdMmYyyy('2026-5-1')).toBe('');
    expect(formatIsoDateDdMmYyyy('2026/05/21')).toBe('');
  });
});

describe('localDateFromIso / isoDateFromLocal — TZ-symmetric range round-trip (T-13)', () => {
  it('parses a YYYY-MM-DD string to LOCAL midnight, not UTC midnight', () => {
    // The defect this fixes: `new Date('2026-06-06')` is UTC midnight, which a
    // local-rendering picker shows as 5 June west of UTC. localDateFromIso binds
    // the date to local calendar fields, so the local day is exactly as authored
    // regardless of the runner's timezone.
    const d = localDateFromIso('2026-06-06')!;
    expect(d.getFullYear()).toBe(2026);
    expect(d.getMonth()).toBe(5); // June (0-based)
    expect(d.getDate()).toBe(6);
    expect(d.getHours()).toBe(0);
  });

  it('round-trips ISO -> Date -> ISO without drifting a day', () => {
    // The flights-list picker round-trip: store ISO -> picker Date (display) ->
    // store ISO (on pick). Symmetric helpers keep the day stable in any zone.
    for (const iso of ['2026-01-01', '2026-06-06', '2026-12-31', '2026-03-29']) {
      expect(isoDateFromLocal(localDateFromIso(iso)!)).toBe(iso);
    }
  });

  it('isoDateFromLocal formats a local Date back to YYYY-MM-DD (zero-padded)', () => {
    expect(isoDateFromLocal(new Date(2026, 0, 3))).toBe('2026-01-03');
    expect(isoDateFromLocal(new Date(2026, 11, 21))).toBe('2026-12-21');
  });

  it('returns null for empty / null / malformed ISO input', () => {
    expect(localDateFromIso(null)).toBeNull();
    expect(localDateFromIso(undefined)).toBeNull();
    expect(localDateFromIso('')).toBeNull();
    expect(localDateFromIso('2026-6-1')).toBeNull();
    expect(localDateFromIso('2026/06/01')).toBeNull();
  });
});
