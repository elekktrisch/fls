import { describe, expect, it } from 'vitest';

import {
  categoryOf,
  cannedDateRange,
  cannedReportSpec,
  DEFAULT_CANNED_FLAGS,
  isCannedType,
  LOCATION_CANNED_TYPES,
  PERSON_CANNED_TYPES,
} from './canned-report';

const TODAY_2026_06_09 = new Date(2026, 5, 9);

describe('cannedDateRange — parity with legacy FlightReportsController.js date math', () => {
  it('today → from = to = today', () => {
    expect(cannedDateRange('today', TODAY_2026_06_09)).toEqual({
      from: '2026-06-09',
      to: '2026-06-09',
    });
  });

  it('yesterday → from = to = today−1', () => {
    expect(cannedDateRange('yesterday', TODAY_2026_06_09)).toEqual({
      from: '2026-06-08',
      to: '2026-06-08',
    });
  });

  it('last-7-days → today−7 … today (INTENDED 8 inclusive days)', () => {
    expect(cannedDateRange('last-7-days', TODAY_2026_06_09)).toEqual({
      from: '2026-06-02',
      to: '2026-06-09',
    });
  });

  it('last-30-days → today−30 … today', () => {
    expect(cannedDateRange('last-30-days', TODAY_2026_06_09)).toEqual({
      from: '2026-05-10',
      to: '2026-06-09',
    });
  });

  it('last-12-months → today−12 months … today', () => {
    expect(cannedDateRange('last-12-months', TODAY_2026_06_09)).toEqual({
      from: '2025-06-09',
      to: '2026-06-09',
    });
  });

  it('last-24-months → today−24 months … today', () => {
    expect(cannedDateRange('last-24-months', TODAY_2026_06_09)).toEqual({
      from: '2024-06-09',
      to: '2026-06-09',
    });
  });

  it('this-year → Jan 1 of current year … today', () => {
    expect(cannedDateRange('this-year', TODAY_2026_06_09)).toEqual({
      from: '2026-01-01',
      to: '2026-06-09',
    });
  });

  it('previous-year → last Jan 1 … last Dec 31', () => {
    expect(cannedDateRange('previous-year', TODAY_2026_06_09)).toEqual({
      from: '2025-01-01',
      to: '2025-12-31',
    });
  });

  it('month arithmetic clamps the way moment().add does at month-end overflow', () => {
    const mar31 = new Date(2026, 2, 31);
    const got = cannedDateRange('last-12-months', mar31);
    expect(got.to).toBe('2026-03-31');
    expect(got.from).toBe('2025-03-31');
  });
});

describe('cannedReportSpec', () => {
  it('combines the derived range with the journey-note default flags (glider+motor on, tow off)', () => {
    const spec = cannedReportSpec('my-flights-last-30-days', TODAY_2026_06_09);
    expect(spec).toEqual({
      from: '2026-05-10',
      to: '2026-06-09',
      gliderFlights: true,
      motorFlights: true,
      towFlights: false,
    });
  });

  it('DEFAULT_CANNED_FLAGS = glider on, motor on, tow off', () => {
    expect(DEFAULT_CANNED_FLAGS).toEqual({
      gliderFlights: true,
      motorFlights: true,
      towFlights: false,
    });
  });
});

describe('type guards + category resolution', () => {
  it('isCannedType accepts every declared canned type, rejects unknowns', () => {
    for (const t of [...PERSON_CANNED_TYPES, ...LOCATION_CANNED_TYPES]) {
      expect(isCannedType(t)).toBe(true);
    }
    expect(isCannedType('not-a-report')).toBe(false);
    expect(isCannedType('')).toBe(false);
  });

  it('categoryOf splits person vs location by the type prefix', () => {
    expect(categoryOf('my-flights-today')).toBe('person');
    expect(categoryOf('location-flights-today')).toBe('location');
  });
});
