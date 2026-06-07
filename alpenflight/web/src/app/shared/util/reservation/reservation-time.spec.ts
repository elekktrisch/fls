import { describe, expect, it } from 'vitest';

import { isoTime, reservationTimeLabel } from './reservation-time';

describe('isoTime', () => {
  it('extracts HH:mm from an ISO instant', () => {
    expect(isoTime('2026-07-01T10:00:00Z')).toBe('10:00');
  });

  it('returns empty string when no time component is present', () => {
    expect(isoTime('2026-07-01')).toBe('');
  });
});

describe('reservationTimeLabel', () => {
  it('renders the start–end window for a timed reservation', () => {
    expect(
      reservationTimeLabel({
        start: '2026-07-01T09:30:00Z',
        end: '2026-07-01T11:45:00Z',
        isAllDay: false,
      }),
    ).toBe('09:30–11:45');
  });

  it('renders the all-day window when isAllDay', () => {
    expect(
      reservationTimeLabel({
        start: '2026-07-01T00:00:00Z',
        end: '2026-07-01T23:59:00Z',
        isAllDay: true,
      }),
    ).toBe('00:00–24:00');
  });
});
