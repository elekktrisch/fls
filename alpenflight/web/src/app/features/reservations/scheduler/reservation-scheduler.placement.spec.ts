import { describe, expect, it } from 'vitest';

import { dayWindow, hourWindow, placeBlock } from './reservation-scheduler.placement';

describe('reservation-scheduler placement', () => {
  const window = dayWindow('2026-07-01T00:00:00Z');

  it('places a timed reservation at the start-time fraction with duration width', () => {
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
    const { leftPct, widthPct } = placeBlock(
      '2026-07-01T23:00:00Z',
      '2026-07-02T01:00:00Z',
      false,
      window,
    );
    expect(leftPct).toBeCloseTo((23 / 24) * 100, 5);
    expect(leftPct + widthPct).toBeLessThanOrEqual(100 + 1e-9);
  });

  it('places a block within a business-hours window (T-39 calendar day view)', () => {
    const win = hourWindow('2026-07-01T10:00:00', 8, 20);
    const { leftPct, widthPct } = placeBlock(
      new Date(2026, 6, 1, 10, 0).toISOString(),
      new Date(2026, 6, 1, 11, 0).toISOString(),
      false,
      win,
    );
    expect(leftPct).toBeCloseTo((2 / 12) * 100, 5);
    expect(widthPct).toBeCloseTo((1 / 12) * 100, 5);
  });
});
