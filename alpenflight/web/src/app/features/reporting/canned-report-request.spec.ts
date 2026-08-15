import { describe, expect, it } from 'vitest';

import { cannedReportRequest } from './canned-report-request';

const TODAY_2026_06_09 = new Date(2026, 5, 9);
const IDS = { personId: 'pn-019e30c3-2c00-7001-8000-000000000001', homebaseLocationId: 'loc-1' };

describe('cannedReportRequest', () => {
  it('person report binds flightCrewPersonId + derived range + default flags', () => {
    const req = cannedReportRequest('my-flights-last-30-days', IDS, TODAY_2026_06_09);
    expect(req.searchFilter).toEqual({
      flightDateFrom: '2026-05-10',
      flightDateTo: '2026-06-09',
      gliderFlights: true,
      motorFlights: true,
      towFlights: false,
      flightCrewPersonId: IDS.personId,
    });
    expect(req.searchFilter?.locationId).toBeUndefined();
  });

  it('location report binds locationId, not flightCrewPersonId', () => {
    const req = cannedReportRequest('location-flights-this-year', IDS, TODAY_2026_06_09);
    expect(req.searchFilter?.locationId).toBe('loc-1');
    expect(req.searchFilter?.flightCrewPersonId).toBeUndefined();
    expect(req.searchFilter?.flightDateFrom).toBe('2026-01-01');
    expect(req.searchFilter?.flightDateTo).toBe('2026-06-09');
  });

  it('omits a null person id (tenant-scoped backend still returns club rows)', () => {
    const req = cannedReportRequest(
      'my-flights-today',
      { personId: null, homebaseLocationId: null },
      TODAY_2026_06_09,
    );
    expect(req.searchFilter?.flightCrewPersonId).toBeUndefined();
    expect(req.searchFilter?.flightDateFrom).toBe('2026-06-09');
  });

  it('omits a null homebase id (location report falls back to club-wide)', () => {
    const req = cannedReportRequest(
      'location-flights-today',
      { personId: IDS.personId, homebaseLocationId: null },
      TODAY_2026_06_09,
    );
    expect(req.searchFilter?.locationId).toBeUndefined();
  });

  it('leaves sorting unset so the backend default sort applies', () => {
    const req = cannedReportRequest('my-flights-today', IDS, TODAY_2026_06_09);
    expect(req.sorting).toBeUndefined();
  });
});
