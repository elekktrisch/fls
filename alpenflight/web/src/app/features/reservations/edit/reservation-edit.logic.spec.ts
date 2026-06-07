import { describe, expect, it } from 'vitest';

import { overlapProbe, sanitizeReturnUrl, secondCrewRequiredFor } from './reservation-edit.page';

const AC_ID = 'ac-019e30c3-2c00-7001-8000-00000000a001';
const RES_ID = 'res-019e30c3-2c00-7001-8000-000000000001';

/** A complete timed-slot raw form value (the shape `getRawValue()` returns). */
function rawValue(over: Partial<Record<string, string | boolean>> = {}) {
  return {
    aircraftId: AC_ID,
    pilotPersonId: '',
    secondCrewPersonId: '',
    locationId: '',
    reservationTypeId: '',
    date: '2026-07-01',
    startTime: '10:00',
    endTime: '11:00',
    isAllDay: false,
    remarks: '',
    ...over,
  } as Parameters<typeof overlapProbe>[0];
}

describe('reservation-edit overlap probe (T-06)', () => {
  it('builds the timed-slot probe with explicit start/end instants', () => {
    expect(overlapProbe(rawValue(), null)).toEqual({
      aircraftId: AC_ID,
      start: '2026-07-01T10:00:00Z',
      end: '2026-07-01T11:00:00Z',
      isAllDay: false,
    });
  });

  it('collapses an all-day slot to midnight start=end and ignores the times', () => {
    expect(overlapProbe(rawValue({ isAllDay: true, startTime: '', endTime: '' }), null)).toEqual({
      aircraftId: AC_ID,
      start: '2026-07-01T00:00:00Z',
      end: '2026-07-01T00:00:00Z',
      isAllDay: true,
    });
  });

  it('passes the edited reservation id as excludeReservationId on an edit', () => {
    const probe = overlapProbe(rawValue(), RES_ID);
    expect(probe?.excludeReservationId).toBe(RES_ID);
  });

  it('omits excludeReservationId on a create (null id)', () => {
    const probe = overlapProbe(rawValue(), null);
    expect(probe).not.toHaveProperty('excludeReservationId');
  });

  it('returns null when the slot is not probe-ready (no aircraft / no date / missing time)', () => {
    expect(overlapProbe(rawValue({ aircraftId: '' }), null)).toBeNull();
    expect(overlapProbe(rawValue({ date: '' }), null)).toBeNull();
    expect(overlapProbe(rawValue({ startTime: '' }), null)).toBeNull();
    expect(overlapProbe(rawValue({ endTime: '' }), null)).toBeNull();
    // An all-day slot is probe-ready even with empty times.
    expect(
      overlapProbe(rawValue({ isAllDay: true, startTime: '', endTime: '' }), null),
    ).not.toBeNull();
  });
});

describe('reservation-edit conditional second-crew (T-18)', () => {
  const plainType = { id: 't1', name: 'Solo', active: true, instructorRequired: false };
  const instructorType = { id: 't2', name: 'Instruction', active: true, instructorRequired: true };
  const singleSeat = {
    id: AC_ID,
    immatriculation: 'HB-1',
    aircraftTypeId: 'at-1',
    isTowingAircraft: false,
    nrOfSeats: 1,
  };
  const multiSeat = { ...singleSeat, nrOfSeats: 2, immatriculation: 'HB-2' };

  it('requires second-crew when the selected type requires an instructor', () => {
    expect(secondCrewRequiredFor(instructorType, singleSeat)).toBe(true);
  });

  it('requires second-crew when the selected aircraft has more than one seat', () => {
    expect(secondCrewRequiredFor(plainType, multiSeat)).toBe(true);
  });

  it('does not require second-crew for a non-instructor type on a single-seat aircraft', () => {
    expect(secondCrewRequiredFor(plainType, singleSeat)).toBe(false);
  });

  it('does not require second-crew when nothing is selected yet (nulls)', () => {
    expect(secondCrewRequiredFor(null, null)).toBe(false);
  });

  it('treats an unknown seat count (absent nrOfSeats) as single-seat', () => {
    const { nrOfSeats: _omit, ...unknownSeats } = singleSeat;
    void _omit;
    expect(secondCrewRequiredFor(plainType, unknownSeats)).toBe(false);
  });
});

describe('reservation-edit cancel returnUrl (T-10)', () => {
  it('uses a valid internal planning-day path', () => {
    expect(sanitizeReturnUrl('/planning/abc-123/edit')).toBe('/planning/abc-123/edit');
    expect(sanitizeReturnUrl('/planning/abc-123/view')).toBe('/planning/abc-123/view');
  });

  it('falls back to /reservations when the param is absent or empty', () => {
    expect(sanitizeReturnUrl(null)).toBe('/reservations');
    expect(sanitizeReturnUrl(undefined)).toBe('/reservations');
    expect(sanitizeReturnUrl('')).toBe('/reservations');
  });

  it('rejects external / off-app targets (no open redirect)', () => {
    // Protocol-relative `//host` would navigate off-app.
    expect(sanitizeReturnUrl('//evil.example.com/phish')).toBe('/reservations');
    // Absolute external URL.
    expect(sanitizeReturnUrl('https://evil.example.com')).toBe('/reservations');
    // Scheme-bearing / javascript: payloads are not rooted paths.
    expect(sanitizeReturnUrl('javascript:alert(1)')).toBe('/reservations');
    // A non-rooted relative value is rejected too.
    expect(sanitizeReturnUrl('planning/abc/edit')).toBe('/reservations');
  });
});
