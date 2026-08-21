import type { LocationDetail } from '@api/generated/model';
import { describe, expect, it } from 'vitest';

import {
  detailToFormValue,
  formToCreateRequest,
  formToUpdateRequest,
} from './locations-edit.mapper';

const baseDetail: LocationDetail = {
  id: 'loc-019e30c3-2c00-7001-8000-000000000001',
  locationName: 'Birrfeld',
  locationShortName: 'BIR',
  countryId: '019e2e15-2c00-74be-8000-0000000004be',
  locationTypeId: '019e2e15-2c00-7110-8000-000000001110',
  icaoCode: 'LSZF',
  latitude: '47.4435',
  longitude: '8.2336',
  description: 'Seed',
  isInboundRouteRequired: true,
  isOutboundRouteRequired: false,
  isFastEntryRecord: true,
  inOutboundPoints: [
    {
      id: '019e30c3-2c00-7002-8000-000000000002',
      pointName: 'Echo',
      pointType: 'IFR',
      direction: 'N',
      description: 'echo waypoint',
    },
  ],
};

describe('locations-edit.mapper', () => {
  it('detailToFormValue replaces nullable fields with empty strings', () => {
    const sparse: LocationDetail = {
      id: baseDetail.id,
      locationName: baseDetail.locationName,
      countryId: baseDetail.countryId,
      locationTypeId: baseDetail.locationTypeId,
      isInboundRouteRequired: false,
      isOutboundRouteRequired: false,
      isFastEntryRecord: false,
      inOutboundPoints: [],
    };
    const form = detailToFormValue(sparse);
    expect(form.locationShortName).toBe('');
    expect(form.icaoCode).toBe('');
    expect(form.latitude).toBe('');
    expect(form.longitude).toBe('');
    expect(form.description).toBe('');
    expect(form.inOutboundPoints).toEqual([]);
  });

  it('detailToFormValue preserves nested IOP rows in order', () => {
    const form = detailToFormValue(baseDetail);
    expect(form.inOutboundPoints).toHaveLength(1);
    expect(form.inOutboundPoints[0]).toEqual({
      pointName: 'Echo',
      pointType: 'IFR',
      direction: 'N',
      description: 'echo waypoint',
    });
  });

  it('formToCreateRequest uppercases ICAO and drops empty strings to undefined', () => {
    const req = formToCreateRequest({
      locationName: '  Birrfeld  ',
      locationShortName: '',
      countryId: 'cc',
      locationTypeId: 'tt',
      icaoCode: 'lszf',
      latitude: '',
      longitude: '   ',
      description: '',
      isInboundRouteRequired: false,
      isOutboundRouteRequired: false,
      isFastEntryRecord: false,
      inOutboundPoints: [],
    });
    expect(req.locationName).toBe('Birrfeld');
    expect(req.icaoCode).toBe('LSZF');
    expect(req.locationShortName).toBeUndefined();
    expect(req.latitude).toBeUndefined();
    expect(req.longitude).toBeUndefined();
    expect(req.description).toBeUndefined();
    expect(req.inOutboundPoints).toEqual([]);
  });

  it('formToCreateRequest filters out blank-named IOP rows', () => {
    const req = formToCreateRequest({
      locationName: 'Saanen',
      locationShortName: '',
      countryId: 'cc',
      locationTypeId: 'tt',
      icaoCode: '',
      latitude: '',
      longitude: '',
      description: '',
      isInboundRouteRequired: false,
      isOutboundRouteRequired: false,
      isFastEntryRecord: false,
      inOutboundPoints: [
        { pointName: 'Echo', pointType: '', direction: '', description: '' },
        { pointName: '   ', pointType: '', direction: '', description: '' },
        { pointName: 'Foxtrot', pointType: 'VFR', direction: 'S', description: 'foxy' },
      ],
    });
    expect(req.inOutboundPoints).toHaveLength(2);
    expect(req.inOutboundPoints?.[0]?.pointName).toBe('Echo');
    expect(req.inOutboundPoints?.[1]?.pointName).toBe('Foxtrot');
    expect(req.inOutboundPoints?.[1]?.pointType).toBe('VFR');
  });

  it('formToUpdateRequest preserves the same shape (no clubId/tenant field)', () => {
    const formValue = detailToFormValue(baseDetail);
    const req = formToUpdateRequest(formValue, formValue.icaoCode);
    expect(req.icaoCode).toBe('LSZF');
    expect(req.inOutboundPoints).toHaveLength(1);
    expect(Object.keys(req)).not.toContain('clubId');
    expect(Object.keys(req)).not.toContain('tenantId');
  });

  it('formToUpdateRequest sends a migrated legacy ICAO verbatim while the operator does not change it', () => {
    const legacyIcaoOutsideThePattern = 'j0cx1';
    const formValue = detailToFormValue({
      ...baseDetail,
      icaoCode: legacyIcaoOutsideThePattern,
    });
    const req = formToUpdateRequest(formValue, legacyIcaoOutsideThePattern);
    expect(req.icaoCode).toBe(legacyIcaoOutsideThePattern);
  });

  it('formToUpdateRequest uppercases an ICAO the operator changed', () => {
    const formValue = detailToFormValue({ ...baseDetail, icaoCode: 'j0cx1' });
    formValue.icaoCode = 'lszk';
    const req = formToUpdateRequest(formValue, 'j0cx1');
    expect(req.icaoCode).toBe('LSZK');
  });
});
