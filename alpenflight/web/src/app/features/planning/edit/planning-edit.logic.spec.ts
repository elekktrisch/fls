import { describe, expect, it } from 'vitest';

import { planningEditLink, uniquenessProbe } from './planning-edit.page';

describe('uniquenessProbe (T-07 planning-day …/validate request builder)', () => {
  const DATE = '2026-07-04';
  const LOCATION = 'loc-019e30c3-2c00-7001-8000-00000000c001';

  it('returns null until BOTH date and location are set', () => {
    expect(uniquenessProbe('', LOCATION, null)).toBeNull();
    expect(uniquenessProbe(DATE, '', null)).toBeNull();
    expect(uniquenessProbe('', '', null)).toBeNull();
  });

  it('builds a create-mode probe (no excludePlanningDayId) once both are set', () => {
    const probe = uniquenessProbe(DATE, LOCATION, null);
    expect(probe).toEqual({ planningDate: DATE, locationId: LOCATION });
    expect(probe && 'excludePlanningDayId' in probe).toBe(false);
  });

  it('sends the edited day id as excludePlanningDayId so it does not self-conflict', () => {
    const editId = '019e30c3-2c00-7001-8000-000000000e01';
    expect(uniquenessProbe(DATE, LOCATION, editId)).toEqual({
      planningDate: DATE,
      locationId: LOCATION,
      excludePlanningDayId: editId,
    });
  });
});

describe('planningEditLink (T-09 read-only → edit navigation target)', () => {
  it('returns null in create mode (no saved id to edit)', () => {
    expect(planningEditLink(null)).toBeNull();
  });

  it('builds the same day’s /edit route so view→edit flips :mode to edit', () => {
    const id = '019e30c3-2c00-7001-8000-000000000e01';
    expect(planningEditLink(id)).toBe(`/planning/${id}/edit`);
  });
});
