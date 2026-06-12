import { describe, expect, it } from 'vitest';

import type { FlightReportSearchFilter } from '@api/generated/model';

import {
  customFilterRequest,
  decodeCustomFilter,
  encodeCustomFilter,
  formToFilter,
  type CustomBuilderValue,
} from './custom-filter';

describe('custom-filter codec', () => {
  it('round-trips a full filter through encode → decode (the AC)', () => {
    const filter: FlightReportSearchFilter = {
      flightDateFrom: '2026-01-01',
      flightDateTo: '2026-12-31',
      gliderFlights: true,
      motorFlights: false,
      towFlights: true,
      locationId: 'loc-019e30c3-2c00-7001-8000-000000000001',
    };
    const encoded = encodeCustomFilter(filter);
    expect(decodeCustomFilter(encoded)).toEqual(filter);
  });

  it('encodes to a single URL-safe segment (no raw braces/quotes/slashes)', () => {
    const encoded = encodeCustomFilter({ gliderFlights: true });
    expect(encoded).not.toContain('{');
    expect(encoded).not.toContain('"');
    expect(encoded).not.toContain('/');
  });

  it('encodes an empty filter to the %7B%7D ({}) default', () => {
    expect(encodeCustomFilter({})).toBe('%7B%7D');
  });

  it('decodes the empty/{} default to an empty filter (not null)', () => {
    expect(decodeCustomFilter('%7B%7D')).toEqual({});
    expect(decodeCustomFilter('')).toEqual({});
    expect(decodeCustomFilter(null)).toEqual({});
    expect(decodeCustomFilter(undefined)).toEqual({});
  });

  it('returns null for a malformed (non-JSON / non-object) segment', () => {
    expect(decodeCustomFilter('not-json')).toBeNull();
    expect(decodeCustomFilter(encodeURIComponent('[1,2]'))).toBeNull();
    expect(decodeCustomFilter(encodeURIComponent('null'))).toBeNull();
  });

  it('wraps a decoded filter into the { searchFilter } page request (sorting unset)', () => {
    const filter: FlightReportSearchFilter = { flightCrewPersonId: 'pn-x' };
    expect(customFilterRequest(filter)).toEqual({ searchFilter: filter });
  });
});

describe('formToFilter (custom-builder form value → FlightReportSearchFilter)', () => {
  const FULL: CustomBuilderValue = {
    from: '2026-01-01',
    to: '2026-12-31',
    glider: true,
    motor: false,
    tow: true,
    scopeId: 'loc-019e30c3-2c00-7001-8000-000000000001',
  };

  it('maps the date range + the three flight-type flags', () => {
    const filter = formToFilter(FULL, 'location');
    expect(filter.flightDateFrom).toBe('2026-01-01');
    expect(filter.flightDateTo).toBe('2026-12-31');
    expect(filter.gliderFlights).toBe(true);
    expect(filter.motorFlights).toBe(false);
    expect(filter.towFlights).toBe(true);
  });

  it('routes the scope id to locationId for the location category', () => {
    const filter = formToFilter(FULL, 'location');
    expect(filter.locationId).toBe(FULL.scopeId);
    expect(filter.flightCrewPersonId).toBeUndefined();
  });

  it('routes the scope id to flightCrewPersonId for the person category', () => {
    const value: CustomBuilderValue = {
      ...FULL,
      scopeId: 'pn-019e30c3-2c00-7001-8000-000000000002',
    };
    const filter = formToFilter(value, 'person');
    expect(filter.flightCrewPersonId).toBe(value.scopeId);
    expect(filter.locationId).toBeUndefined();
  });

  it('omits an empty From/To/scope (the backend defaults / tenant-scopes those)', () => {
    const value: CustomBuilderValue = {
      from: '',
      to: '',
      glider: true,
      motor: true,
      tow: false,
      scopeId: '',
    };
    const filter = formToFilter(value, 'location');
    expect('flightDateFrom' in filter).toBe(false);
    expect('flightDateTo' in filter).toBe(false);
    expect('locationId' in filter).toBe(false);
    expect('flightCrewPersonId' in filter).toBe(false);
    expect(filter.gliderFlights).toBe(true);
    expect(filter.towFlights).toBe(false);
  });
});
