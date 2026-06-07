import { describe, expect, it } from 'vitest';

import { formatDdMmYyyy, formatIsoDateDdMmYyyy } from './format-date';

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
