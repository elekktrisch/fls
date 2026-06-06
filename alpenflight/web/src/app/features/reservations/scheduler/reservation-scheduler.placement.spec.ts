import { describe, expect, it } from 'vitest';

import { dayWindow, placeBlock } from './reservation-scheduler.placement';

/**
 * Placement math for the scheduler view (J-5 T-10) — the load-bearing
 * "reservation at time T in aircraft A → offset f(T)" assertion, unit-tested
 * here so the e2e only has to prove the lane×block wiring. Three cases: a timed
 * block lands at the right fraction of the day; an all-day block fills the
 * window; an out-of-window start clamps inside the lane.
 */
describe('reservation-scheduler placement', () => {
  const window = dayWindow('2026-07-01T00:00:00Z'); // [00:00, 24:00) UTC

  it('places a timed reservation at the start-time fraction with duration width', () => {
    // 10:00–11:00 → left 10/24, width 1/24 of the day.
    const { leftPct, widthPct } = placeBlock(
      '2026-07-01T10:00:00Z',
      '2026-07-01T11:00:00Z',
      false,
      window,
    );
    expect(leftPct).toBeCloseTo((10 / 24) * 100, 5);
    expect(widthPct).toBeCloseTo((1 / 24) * 100, 5);
  });

  it('renders an all-day reservation as a full-width band', () => {
    const { leftPct, widthPct } = placeBlock(
      '2026-07-01T00:00:00Z',
      '2026-07-02T00:00:00Z',
      true,
      window,
    );
    expect(leftPct).toBe(0);
    expect(widthPct).toBe(100);
  });

  it('clamps a block that runs past the window end so it stays inside the lane', () => {
    // 23:00 → 01:00 next day: left 23/24, width clamped to the remaining 1/24.
    const { leftPct, widthPct } = placeBlock(
      '2026-07-01T23:00:00Z',
      '2026-07-02T01:00:00Z',
      false,
      window,
    );
    expect(leftPct).toBeCloseTo((23 / 24) * 100, 5);
    expect(leftPct + widthPct).toBeLessThanOrEqual(100 + 1e-9);
  });
});
